package com.geeksville.andropilot.gui

import android.os.Bundle
import android.widget.ArrayAdapter
import scala.collection.JavaConverters._
import com.geeksville.util.ThreadTools._
import android.support.v4.app.Fragment
import android.view.LayoutInflater
import android.view.ViewGroup
import com.ridemission.scandroid.AndroidUtil._
import com.geeksville.andropilot.TypedResource._
import com.geeksville.andropilot.TR
import android.widget.ArrayAdapter
import com.geeksville.flight._
import java.util.LinkedList
import com.geeksville.andropilot.R
import android.view.View
import android.graphics.Color
import com.geeksville.andropilot.service.AndropilotService
import android.widget.Button
import android.view.ViewGroup.LayoutParams
import android.widget.LinearLayout
import android.view.Gravity
import com.ridemission.scandroid.SimpleDialogClient
import com.geeksville.mavlink.MsgArmChanged

class ModalFragment extends LayoutFragment(R.layout.modal_bar) with AndroServiceFragment with SimpleDialogClient {
  private def uploadWpButton = Option(getView).map(_.findView(TR.upload_waypoint_button))
  private def modeTextView = Option(getView).map(_.findView(TR.mode_text))
  private def modeButtonGroup = Option(getView).map(_.findView(TR.mode_buttons))

  val errColor = Color.RED
  val warnColor = Color.YELLOW
  val okayColor = Color.GREEN

  private def postModeChange() {
    handler.post { () =>
      setModeFromVehicle()
      setButtons()
    }
  }

  override def onVehicleReceive = {

    case MsgWaypointDirty(dirty) =>
      handler.post { () =>
        setWPUploadVisibility(dirty)
      }

    case MsgArmChanged(_) =>
      postModeChange()

    case MsgModeChanged(_) =>
      postModeChange()

    case MsgFSMChanged(_) =>
      postModeChange()

    case StatusText(s, severity) =>
      val isImportant = severity >= MsgStatusChanged.SEVERITY_HIGH
      handler.post { () =>
        handleStatus(s)
      }
  }

  private def setWPUploadVisibility(dirty: Boolean) {
    uploadWpButton.foreach(_.setVisibility(if (dirty) View.VISIBLE else View.GONE))
  }

  override def onActivityCreated(savedInstanceState: Bundle) {
    super.onActivityCreated(savedInstanceState)

    myVehicle.foreach { v =>
      setWPUploadVisibility(v.isDirty)
    }

    uploadWpButton.get.onClick { b =>
      myVehicle.foreach { v =>
        v ! SendWaypoints
      }
    }
  }

  override protected def onServiceConnected(s: AndropilotService) {
    super.onServiceConnected(s)
    setModeFromVehicle()
    setButtons()
  }

  def setModeText(str: String, color: Int) {
    modeTextView.foreach { view =>
      // Fade in the new text
      if (view.getText.toString != str) {
        fadeIn(view)
      }

      view.setTextColor(color)
      view.setText(str)

      view.setVisibility(View.VISIBLE)
    }
  }

  private def handleStatus(msg: String) {
    val color = if (msg.contains("Failure")) errColor else okayColor
    setModeText(msg, color)

    // Go back to the regular status text after a few secs
    handler.postDelayed({ () => setModeFromVehicle() }, 8 * 1000)
  }

  private def fadeIn(v: View) {
    v.setAlpha(0f)
    v.animate().alpha(1f).setDuration(600)
  }

  private def makeButton(name: String) = {
    val button = new Button(getActivity)
    button.setText(name)
    button.setTextSize(12.0f)
    button.setBackgroundResource(R.drawable.custombutton)
    fadeIn(button)

    val lp = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, 0f)
    lp.gravity = Gravity.CENTER
    lp.leftMargin = 6
    //lp.setMarginStart(4) - not supported on older android builds
    modeButtonGroup.foreach(_.addView(button, lp))
    button
  }

  private val testModeNames = Seq("RTL" -> false, "STABILIZE" -> false, "FISH" -> false, "Arm" -> false)

  private def setButtons() {
    for {
      v <- myVehicle
      s <- service
      bgrp <- modeButtonGroup
    } yield {
      bgrp.removeAllViews()

      debug(s"in setButtons heartbeat=${v.hasHeartbeat}")

      // Show the vehicle mode buttons
      val modenames = if (s.isConnected && v.hasHeartbeat)
        v.selectableModeNames
      else
        Seq() // testModeNames

      modenames.foreach {
        case (name, confirm) =>
          val b = makeButton(name)

          if (confirm)
            confirmButtonPress(b, s"Switch to $name mode?") { () => v ! DoSetMode(name) }
          else
            b.onClick { b => v ! DoSetMode(name) }
      }

      // Add a special button to turn bluetooth on/off
      if (s.bluetoothAdapterPresent)
        if (!s.isConnected)
          makeButton("Bluetooth").onClick { b =>
            s.connectToDevices()
          }
        else if (!v.isArmed)
          confirmButtonPress(makeButton("Disconnect"), "Disconnect bluetooth?") { () =>
            s.forceBluetoothDisconnect()
          }
    }
  }

  private def setModeFromVehicle() {
    val (msg, color) = myVehicle match {
      case Some(v) =>
        v.fsm.getState.getName match {
          case "VehicleFSM.WantInterface" =>
            "Looking for radio" -> errColor
          case "VehicleFSM.WantVehicle" =>
            "Looking for vehicle" -> errColor
          case "VehicleFSM.DownloadingWaypoints" =>
            "Downloading waypoints..." -> warnColor
          case "VehicleFSM.DownloadingParameters" =>
            "Downloading parameters..." -> warnColor
          case "VehicleFSM.DownloadedParameters" =>
            "Downloaded params (BUG!)" -> errColor
          case "VehicleFSM.Disarmed" =>
            "Disarmed" -> errColor
          case "VehicleFSM.Armed" =>
            "Armed" -> warnColor
          case "VehicleFSM.Flying" =>
            v.currentMode -> okayColor
        }
      case None =>
        "No vehicle (BUG!)" -> errColor
    }

    setModeText(msg, color)
  }
}
