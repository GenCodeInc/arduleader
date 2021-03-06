package com.geeksville.gcsapi

import com.geeksville.flight.VehicleModel
import com.ridemission.rest.JsonConverters._

/**
 * This is the singleton used to access vehicle and GCS state from javascript or other languages.
 *
 * See SmallAPI for the design philosophy of this object.
 */
class VehicleAdapter(v: VehicleModel) extends SmallAdapter {
  def getters = Map("location" -> (() => "fish".asJson))
}