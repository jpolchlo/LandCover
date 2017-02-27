package com.azavea.landcover.learning

import com.azavea.landcover.common._
import geotrellis.proj4.CRS
import geotrellis.vector._
import geotrellis.vector.io._
import geotrellis.vector.io.json._

import java.util.Date

//import scala.collection.immutable.Map

object Learning {
  val usMP: MultiPolygon = {
    val filename = "/data/Boundaries/Lower_48.geojson"
    val json = GeoJson.fromFile[JsonFeatureCollection](filename)
    json.getAll[GeometryCollection].apply(0).geometries(0).asInstanceOf[MultiPolygon]
  }

  val nlcdProj = CRS.fromString("+proj=aea +lat_1=29.5 +lat_2=45.5 +lat_0=23 +lon_0=-96 +x_0=0 +y_0=0 +ellps=GRS80 +datum=NAD83 +units=m +no_defs ")

  def intersectingTiles(cat: Catalog, usReprojected: MultiPolygon): Seq[(Char, Char)] = {
    val keys = cat.allKeys
    val extents = cat.getExtents
    keys.toSeq.filter{ key => {
      val ex = extents(key)
      usReprojected.intersects(ex)
    }}
  }

  val usZones = for ( z <- 10 to 19 ; b <- "NPQRSTU" ) yield { (z, b) }
  val atlas = (for ( zone <- usZones ) yield { (zone, new Catalog(CommandLine.Params())(zone._1, zone._2)) }).toMap

  val allIntersectingTiles = {
    usZones.flatMap { zone => {
      val cat = atlas(zone)
      val zonecrs = cat.getCRS
      val usProjected = usMP.reproject(geotrellis.proj4.LatLng, zonecrs)
      intersectingTiles(cat, usProjected).map { key => (zone, key) }
    }}
  }

  def cloudAtlas(sampleSize: Int, threshold: Double = 0.99) = {
    val sample = scala.util.Random.shuffle(allIntersectingTiles).take(sampleSize)
    val sampleByZone: Map[(Int, Char), Seq[(Char, Char)]] = sample.groupBy(_._1).mapValues(_.map(_._2))
    sampleByZone.toSeq.flatMap { case (zone, keys) => {
      val cloudy: Seq[(Date, (Char, Char), Int)] = atlas(zone).filterKeys{ (_, cloudy) => cloudy > threshold }
      cloudy.filter{ case (_, k, _) => keys.contains(k) }.map( (zone, _) )
    }}
  }
}
