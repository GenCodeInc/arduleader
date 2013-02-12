package com.geeksville.andropilot.gui
import android.widget.ListView
import android.view.View
import scala.collection.JavaConverters._
import android.widget.SimpleAdapter
import com.geeksville.flight._
import com.geeksville.util.ThreadTools._
import android.support.v4.app.ListFragment
import com.geeksville.andropilot.R
import com.geeksville.andropilot.service._

class WaypointListFragment extends ListFragment with AndroServiceFragment {

  override def onServiceConnected(s: AndropilotService) {
    super.onServiceConnected(s)

    // Don't expand the view until we have _something_ to display
    debug("parameter list service connected")
    handleWaypoints()
  }

  override def onVehicleReceive = {

    case MsgWaypointsChanged =>
      handler.post(handleWaypoints _)
  }

  private def handleWaypoints() {
    // Don't expand the view until we have _something_ to display
    if (getView != null) {
      debug("updating parameters")
      makeAdapter.foreach(setListAdapter)
    }
  }

  override def onListItemClick(l: ListView, v: View, position: Int, id: Long) {
    info("Item clicked: " + id)

    myVehicle.foreach { v =>
      if (position < v.waypoints.size) {
        // FIXME
      }
    }
  }

  private def makeAdapter() =
    for (v <- myVehicle if !v.waypoints.isEmpty) yield {
      debug("Setting waypoint list to " + v.waypoints.size + " items")

      val asMap = v.waypoints.zipWithIndex.map {
        case (p, i) =>
          Map("n" -> i, "name" -> p.longString).asJava
      }.asJava
      val fromKeys = Array("n", "name")
      val toFields = Array(R.id.waypoint_number, R.id.waypoint_name)
      new SimpleAdapter(getActivity, asMap, R.layout.waypoint_row, fromKeys, toFields)
    }
}
