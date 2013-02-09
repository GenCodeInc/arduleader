package com.geeksville.andropilot.gui

import _root_.android.os.Bundle
import android.content.Intent
import com.ridemission.scandroid._
import com.ridemission.scandroid.AndroidUtil._
import com.google.android.gms.common.GooglePlayServicesUtil
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.maps.MapFragment
import android.widget._
import com.google.android.gms.maps.GoogleMap
import android.content.ServiceConnection
import android.content.ComponentName
import android.os.IBinder
import android.content.Context
import com.google.android.gms.maps.model._
import android.content.res.Configuration
import com.geeksville.flight.VehicleMonitor
import com.geeksville.flight.Location
import com.geeksville.akka.MockAkka
import com.geeksville.mavlink.MavlinkEventBus
import android.os.Handler
import com.geeksville.util.ThreadTools._
import scala.language.postfixOps
import android.hardware.usb.UsbManager
import android.content.BroadcastReceiver
import android.content.IntentFilter
import com.geeksville.util.Throttled
import com.google.android.gms.maps.CameraUpdateFactory
import android.view.View
import com.geeksville.akka.PoisonPill
import android.view.Menu
import android.widget.AdapterView.OnItemSelectedListener
import com.geeksville.gmaps.Scene
import org.mavlink.messages.ardupilotmega.msg_mission_item
import com.geeksville.gmaps.Segment
import com.google.android.gms.maps.GoogleMap.OnMapLongClickListener
import android.view.MenuItem
import com.ridemission.scandroid.UsesPreferences
import com.geeksville.akka.InstrumentedActor
import com.geeksville.flight._
import com.geeksville.mavlink._
import android.support.v4.app.Fragment
import android.support.v4.app.FragmentPagerAdapter
import android.support.v4.app.FragmentActivity
import android.support.v4.view._
import android.support.v4.app.FragmentStatePagerAdapter
import android.view.ViewGroup
import com.geeksville.aspeech.TTSClient
import com.geeksville.util.ThrottleByBucket
import com.geeksville.andropilot.service._
import com.geeksville.andropilot._

class MainActivity extends FragmentActivity with TypedActivity
  with AndroidLogger with FlurryActivity with UsesPreferences with TTSClient
  with AndroServiceClient {

  implicit def context = this

  /**
   * If the user just changed the mode menu, ignore device mode msgs briefly
   */
  private var ignoreModeChangesTill = 0L

  private var mainView: View = null
  private var modeSpinner: Option[Spinner] = None

  /**
   * If an intent arrives before our service is up, squirel it away until we can handle it
   */
  private var waitingForService: Option[Intent] = None

  private var watchingSerial = false
  private var accessGrantReceiver: Option[BroadcastReceiver] = None

  private val stdPages = IndexedSeq(
    PageInfo("Overview", { () => new OverviewFragment }),
    PageInfo("Parameters", { () => new ParameterListFragment }),
    PageInfo("Waypoints", { () => new WaypointListFragment }),
    PageInfo("HUD", { () => new HudFragment }),
    PageInfo("RC Channels", { () => new RcChannelsFragment }))

  /**
   * If we don't have enough horizontal width - the layout will move the map into the only (pager) view.
   * Make it the first/default page
   */
  private val phonePages = PageInfo("Map", { () => new MyMapFragment }) +: stdPages

  // We don't cache these - so that if we get rotated we pull the correct one
  // Also - might not always be present, so we make it an option
  def mapFragment = Option(getFragmentManager.findFragmentById(R.id.map).asInstanceOf[MyMapFragment])
  def viewPager = Option(findViewById(R.id.pager).asInstanceOf[ViewPager])

  /**
   * Does work in the GUIs thread
   */
  var handler: Handler = null

  private var oldVehicleType: Option[Int] = None

  private lazy val throttleAlt = new ThrottleByBucket(intPreference("speech_altbucket", 10))
  private val throttleBattery = new ThrottleByBucket(10)

  /**
   * We install this receiver only once we're connected to a device -
   * only used to show a Toast about disconnection...
   */
  val disconnectReceiver = new BroadcastReceiver {
    override def onReceive(context: Context, intent: Intent) {
      if (intent.getAction == UsbManager.ACTION_USB_DEVICE_DETACHED)
        serialDetached()
    }
  }

  override def onVehicleReceive = {

    case l: Location =>
      throttleAlt(l.alt.toInt) { alt =>
        handler.post { () =>
          debug("Speak alt: " + alt)
          speak(alt + " meters")
        }
      }

    case MsgSysStatusChanged =>
      for { v <- myVehicle; pct <- v.batteryPercent } yield {
        throttleBattery((pct * 100).toInt) { pct =>
          handler.post { () =>
            debug("Speak battery: " + pct)
            speak(pct + " percent")
          }
        }
      }

    case MsgModeChanged(_) =>
      handler.post { () =>
        myVehicle.foreach { v =>
          if (oldVehicleType != v.vehicleType) {
            oldVehicleType = v.vehicleType
            setModeOptions()
          }
          setModeSpinner() // FIXME, do this someplace better
        }
      }

    case MsgStatusChanged(s) =>
      debug("Status changed: " + s)
      handler.post { () =>
        toast(s)
      }

    case MsgParametersDownloaded =>
      handler.post(handleParameters _)
  }

  override def onServiceConnected(s: AndropilotService) {
    toast(s.logmsg)

    // If we already had a serial port open start watching it
    registerSerialReceiver()

    // Ask for any already connected serial devices
    //requestAccess()

    // If the menu is already up - update the set of options & selected mode
    setModeOptions()
    setModeSpinner()

    waitingForService.foreach { intent =>
      handleIntent(intent)
      waitingForService = None
    }
  }

  override def onCreate(bundle: Bundle) {
    super.onCreate(bundle)

    debug("Main onCreate")
    // warn("GooglePlayServices = " + GooglePlayServicesUtil.isGooglePlayServicesAvailable(this))

    mainView = getLayoutInflater.inflate(R.layout.main, null)
    setContentView(mainView)

    // textView.setText("hello, world!")

    handler = new Handler

    // Set up the ViewPager with the sections adapter (if it is present on this layout)
    viewPager.foreach { v =>
      val adapter = Option(v.getAdapter.asInstanceOf[ScalaPagerAdapter])

      warn("Need to set pager adapter")
      v.setAdapter(sectionsPagerAdapter)
    }

    val probe = GooglePlayServicesUtil.isGooglePlayServicesAvailable(this)
    if (probe == ConnectionResult.SUCCESS) {
      // Did the user just plug something in?
      Option(getIntent).foreach(handleIntent)
    } else {
      Option(findView(TR.maps_error)).map { v =>
        val msg = if (probe == ConnectionResult.SERVICE_INVALID)
          """|Google Maps can not be embedded - Google reports that 'Google Play Services' is not authentic.
             |(If you are seeing this message and using a 'hobbyist' ROM, check with the
             |person who made that ROM - it seems like they made a mistake repackaging the service)""".stripMargin
        else
          """|Google Maps is not installed (code=%d) - you will not be able to run this application... 
             |(If you are seeing this message and using a 'hobbyist' ROM, check with the
             |person who made that ROM - it seems like they forgot to include a working version of 'Google
             |Play Services')""".stripMargin.format(probe)
        v.setText(msg)
        v.setVisibility(View.VISIBLE)

        // Alas - this seems to not work
        // GooglePlayServicesUtil.getErrorDialog(probe, this, 1).show()
      }.getOrElse {
        error("Some chinese WonderMe device is out there failing to find google maps, sorry - you are out of luck")
      }
      for { map <- mapFragment; view <- Option(map.getView) } yield { view.setVisibility(View.GONE) }
    }

    initSpeech()
  }

  // Workaround to make sure child fragment state is not saved on orientation page (makes fragment panes show correctly)
  // http://stackoverflow.com/questions/13910826/viewpager-fragmentstatepageradapter-orientation-change
  override def onSaveInstanceState(outState: Bundle) {
    // super.onSaveInstanceState(outState);
  }

  private def sectionsPagerAdapter = {

    new ScalaPagerAdapter(getSupportFragmentManager, pages) {

      // Force views to get recreated on orientation change - http://stackoverflow.com/questions/7263291/viewpager-pageradapter-not-updating-the-view
      override def getItemPosition(obj: Object) = {
        PagerAdapter.POSITION_NONE
      }
    }
  }

  private def pages = {
    val isWide = viewPager.map(_.getTag == "with-sidebar").getOrElse(false)
    val r = if (isWide) stdPages else phonePages
    debug("Using wide view=" + isWide + " pages=" + r.mkString(","))
    r
  }

  /**
   * We are a singleTop app, so when other intents arrive we will not start a new instance, rather handle them here
   */
  override def onNewIntent(i: Intent) {
    handleIntent(i)
  }

  override def onResume() {
    super.onResume()

    serviceOnResume()
  }

  override def onPause() {
    serviceOnPause()

    accessGrantReceiver.foreach { r =>
      unregisterReceiver(r)
      accessGrantReceiver = None
    }

    unregisterSerialReceiver()

    super.onPause()
  }

  override def onDestroy() {
    destroySpeech()

    super.onDestroy()
  }

  private def toast(str: String) {
    Toast.makeText(this, str, Toast.LENGTH_LONG).show()
  }

  private def handleParameters() {
    // Our parameters are valid, perhaps write them to disk (FIXME, this really should be done in the service)

    if (boolPreference("params_to_file", true))
      for { dir <- AndropilotService.paramDirectory; vm <- myVehicle } yield {
        val file = ParameterFile.getFilename(dir)
        try {
          ParameterFile.create(vm.parameters, file)
          toast("Parameters backed up to " + dir)
        } catch {
          case ex: Exception =>
            error("Can't write param file: " + ex.getMessage)
        }
      }
  }

  private def serialDetached() {
    debug("Handling serial disconnect")
    unregisterSerialReceiver()

    toast("3DR Telemetry disconnected...")
  }

  private def unregisterSerialReceiver() {
    if (watchingSerial) {
      unregisterReceiver(disconnectReceiver)
      watchingSerial = false
    }
  }

  private def registerSerialReceiver() {
    service.foreach { s =>
      if (!watchingSerial && s.isSerialConnected) {
        // Find out when the device goes away
        registerReceiver(disconnectReceiver, new IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED))
        watchingSerial = true
      }
    }
  }

  private def handleIntent(intent: Intent) {
    debug("Received intent: " + intent)
    service.map { s =>
      intent.getAction match {
        case Intent.ACTION_MAIN =>
          // Normal app start - just ask for access to any connected devices
          requestAccess()

        case UsbManager.ACTION_USB_DEVICE_ATTACHED =>
          if (AndroidSerial.getDevice.isDefined) {
            // speak("Connected")
            toast("3DR Telemetry connected...")
          } else
            warn("Ignoring attach for some other device")

        case x @ _ =>
          error("Ignoring unknown intent: " + intent)
      }
    }.getOrElse {
      // No service yet, store the intent until we can do something about it
      waitingForService = Some(intent)
    }
  }

  /**
   * Update our mode display
   */
  def setModeSpinner() {
    if (System.currentTimeMillis > ignoreModeChangesTill)
      modeSpinner.foreach { s =>
        // Crufty way of finding which element of spinner needs selecting
        def findIndex(str: String) = {
          val adapter = s.getAdapter

          (0 until adapter.getCount).find { i =>
            val is = adapter.getItem(i).toString
            is == str || is == "unknown"
          }.get
        }
        myVehicle.foreach { v =>
          speak(v.currentMode, true)
          val n = findIndex(v.currentMode)
          //debug("Setting mode spinner to: " + n)

          s.setSelection(n)
        }
      }
  }

  /**
   * This really useful method is not on ICS, alas...
   */
  private def getThemedContext = {
    try {
      getActionBar.getThemedContext
    } catch {
      case ex: NoSuchMethodError =>
        this
    }
  }

  /**
   * Update the set of options in the mode menu (called when vehicle type changes)
   */
  private def setModeOptions() {
    for { s <- modeSpinner; v <- myVehicle } yield {
      val spinnerAdapter = new ArrayAdapter(getThemedContext, android.R.layout.simple_spinner_dropdown_item, v.modeNames.toArray)
      // val spinnerAdapter = ArrayAdapter.createFromResource(getThemedContext, R.array.mode_names, android.R.layout.simple_spinner_dropdown_item); //  create the adapter from a StringArray
      s.setAdapter(spinnerAdapter); // set the adapter
    }
  }

  override def onCreateOptionsMenu(menu: Menu) = {
    debug("Creating option menu")
    getMenuInflater.inflate(R.menu.action_bar, menu) // inflate the menu
    val s = menu.findItem(R.id.menu_mode).getActionView().asInstanceOf[Spinner] // find the spinner
    modeSpinner = Some(s)
    setModeOptions()
    setModeSpinner()

    menu.findItem(R.id.menu_speech).setChecked(isSpeechEnabled)

    def modeListener(parent: Spinner, selected: View, pos: Int, id: Long) {
      val modeName = s.getAdapter.getItem(pos)
      debug("Mode selected: " + modeName)
      myVehicle.foreach { v =>
        if (modeName != "unknown" && modeName != v.currentMode) {
          // Give up to two seconds before we pay attention to mode msgs - so we don't get confused by stale msgs in our queue
          ignoreModeChangesTill = System.currentTimeMillis + 2000
          v.setMode(modeName.toString)
        }
      }
    }
    s.onItemSelected(modeListener) // (optional) reference to a OnItemSelectedListener, that you can use to perform actions based on user selection

    true
  }

  override def onOptionsItemSelected(item: MenuItem) = {
    item.getItemId match {
      case R.id.menu_settings =>
        startActivity(new Intent(this, classOf[SettingsActivity]))
      case R.id.menu_speech =>
        val n = !item.isChecked
        debug("Toggle speech, newmode " + n)
        isSpeechEnabled = n
        item.setChecked(n)

      case R.id.menu_followme => // FIXME - move this into the map fragment
        service.foreach { s =>
          val n = !item.isChecked
          debug("Toggle followme, newmode " + n)
          s.setFollowMe(n)

          item.setChecked(n)
        }
      case _ =>
    }

    super.onOptionsItemSelected(item)
  }

  /** Ask for permission to access our device */
  def requestAccess() {
    warn("Requesting USB access")
    AndroidSerial.getDevice match {
      case Some(device) =>
        accessGrantReceiver = Some(AndroidSerial.requestAccess(device, { d =>

          // Do nothing in here - we will receive a USB attached event.  Only need to post a message if the user _denyed_ access
          warn("USB access received")

          handler.post { () =>
            service.foreach { s =>
              if (!s.isSerialConnected) {
                toast("Connecting link...")
                s.serialAttached()
              }
            }
          }
        }, { d =>

          // This gets called from inside our broadcast receiver - apparently the device is not ready yet, so queue some work for 
          // our GUI thread
          // requestAccess is not called until the service is up, so we can safely access this
          // If we are already talking to the serial device ignore this

          handler.post { () =>
            toast("User denied access to USB device")
          }
        }))
      case None =>
        toast("Please attach telemetry or APM")
      // startService() // FIXME, remove this
    }
  }
}
