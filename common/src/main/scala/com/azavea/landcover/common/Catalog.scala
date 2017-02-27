package com.azavea.landcover.common

import S3Requests.SentinelTileInfo

import geotrellis.proj4.CRS
import geotrellis.vector.Extent

import java.awt.{BorderLayout, Image}
import java.io.{File, FileInputStream, FileOutputStream, ObjectInputStream, ObjectOutputStream}
import java.util.Date
import java.text.SimpleDateFormat
import javax.swing.{JDialog, JLabel, JProgressBar}
import scala.collection.mutable.{Map, Set}
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration.Duration

object Catalog {
  def filterCatalog(cat: Catalog)(f: (Date, (Char, Char), Int, SentinelTileInfo) => Boolean): Catalog = {
    val catmap = cat.catalog
    val newcat = new Catalog(cat.params)(cat.zoneNumber, cat.latitudeBand)
    val filtered = catmap.map{ case (date, list) => (date, list.filter{ case (key, num, info) => f(date, key, num, info)}) }
                         .filter{ x => !(x._2.isEmpty) }
    newcat.loadCatalogRaw(filtered)
    newcat.loadTilesAtKey
    newcat
  }
  
  def apply(params: CommandLine.Params)(zoneNumber: Int, latitudeBand: Char) = { 
    val cat = new Catalog(params)(zoneNumber, latitudeBand) 
    cat.populateCatalog
    cat
  }
}

class Catalog private (val params: CommandLine.Params)(private var zoneNumber: Int, private var latitudeBand: Char) {
  private val cache = Map.empty[(Int, Char, (Date, Char, Char, Int)), Option[Promise[Image]]]
  private var keys: Set[(Char, Char)] = null
  // /*private*/ var catalog: Map[Date, List[((Char, Char), Int, Extent, Double)]] = null
  var catalog: Map[Date, List[((Char, Char), Int, SentinelTileInfo)]] = null
  private val tilesAtKey = Map.empty[(Char, Char), List[(Date, Int)]]
  private val keyExtents = Map.empty[(Char, Char), Extent]

  implicit val ec = ExecutionContext.global

  def loadCatalogFromS3(catfile: File) = {
    println(s"Loading SENTINEL catalog from S3 into $catfile")
    val coverage = S3Requests.getSentinelCoverage(params, zoneNumber, latitudeBand)
    val oos = new ObjectOutputStream(new FileOutputStream(catfile))
    val toWrite = coverage._2.mapValues(_.toVector).toSeq: Seq[(Date, Vector[((Char,Char), Int, SentinelTileInfo)])]
    oos.writeObject(toWrite)
    oos.close
    catalog = coverage._2
    keys = Set(coverage._1: _*)
  }

  def loadCatalogFromFile(catfile: File) = {
    println("Reading catalog from file")
    val ois = new ObjectInputStream(new FileInputStream(catfile))
    val obj = ois.readObject.asInstanceOf[Seq[(Date, Vector[((Char, Char), Int, SentinelTileInfo)])]].map { case (d,v) => (d, v.toList) }
    catalog = scala.collection.mutable.Map(obj: _*)
    keys = Set.empty[(Char,Char)]
    catalog.values.foreach{ l: List[((Char, Char), Int, SentinelTileInfo)] => {
      l.foreach{ x: ((Char, Char), Int, SentinelTileInfo) => keys += x._1 }
    }}
  }

  def loadCatalogRaw(cat: Map[Date, List[((Char, Char), Int, SentinelTileInfo)]]) = {
    catalog = cat
    keys = Set.empty[(Char,Char)]
    catalog.values.foreach{ l: List[((Char, Char), Int, SentinelTileInfo)] => {
      l.foreach{ x: ((Char, Char), Int, SentinelTileInfo) => keys += x._1 }
    }}
  }

  // def ensureLiveAndClear() = {
  //   def isCloudy(key: (Char, Char))(elem: (Date, Int)) = {
  //     print(".")
  //     val (date, num) = elem
  //     preload(date, (key, num))
  //     val (_, cloudy, _) = cache((zoneNumber, latitudeBand, (date, key._1, key._2, num)))
  //     cloudy > params.cloudyThreshold
  //   }

  //   println("Removing cloudy tiles")
  //   tilesAtKey.par.foreach { case (key, list) => {
  //     print(s"    Tile ${key._1}${key._2}")
  //     tilesAtKey += key -> list.dropWhile(isCloudy(key))
  //     println
  //   }}
  // }

  def loadTilesAtKey() = {
    println("Building spatial key index...")
    tilesAtKey.clear
    catalog.foreach{ case (date, keys) => {
      keys.foreach { case (key, num, _) => {
        tilesAtKey get key match {
          case Some(list) => tilesAtKey += key -> ((date, num) :: list)
          case None => tilesAtKey += key -> List((date, num))
        }
      }}
    }}
    tilesAtKey.foreach{ case (k, list) => tilesAtKey put (k, list.sorted) }

    println("Loading extents...")
    keyExtents.clear
    keys.par.foreach{ key: (Char, Char) => {
      println(s"\tReading extent for ${key._1}${key._2}")
      val (date, num) = tilesAtKey(key).head
      val extent = catalog(date).filter{ case (k, n, _) => k == key && n == num }.head._3.tileExtent
      keyExtents += key -> extent
    }}
  }

  def allKeys() = keys
  def getZone() = (zoneNumber, latitudeBand)
  def getExtents() = scala.collection.immutable.Map(keyExtents.toSeq: _*)
  def getBoundary() = keyExtents.values.reduce(_.expandToInclude(_))
  def getCRS(): CRS = {
    val zoneString = "%02d".format(zoneNumber)
    CRS.fromName(s"EPSG:326${zoneString}")
  }

  def filterKeys(f: (Extent, Double) => Boolean): Seq[(Date, (Char, Char), Int)] = {
    catalog.toSeq.flatMap{ case (date, list) => {
      list.filter { case (_, _, info) => f(info.tileExtent, info.cloudyPercent) }.map { case (key, num, _) => (date, key, num) }
    }}
  }

  def populateCatalog() = {
    val catfile = new java.io.File(s"newcatalog${zoneNumber}${latitudeBand}.obj")
    if (!catfile.exists || params.forceCatalogLoad) {
      loadCatalogFromS3(catfile)
    } else {
      loadCatalogFromFile(catfile)
    }
    loadTilesAtKey
    //ensureLiveAndClear
  }

  def resetZone(zone: Int, latitude: Char) = {
    zoneNumber = zone
    latitudeBand = latitude
    populateCatalog
  }

  def preload(date: Date, key: ((Char, Char), Int)) = {
    val ((row, col), num) = key
    val query = (date, row, col, num)

    if (!cache.contains((zoneNumber, latitudeBand, query))) {
      val cloudy = catalog(date).find{ case (k, n, _) => k == key._1 && n == num }.get._3.cloudyPercent
      val imageMaybe =
        if (cloudy < params.cloudyThreshold) {
          val p = Promise[Image]
          Future {
            val img = S3Requests.readPreviewImage(params, zoneNumber, latitudeBand)(date, key)
            p success img
          }
          Some(p)
        } else {
          None
        }
      this.synchronized {
        cache += (zoneNumber, latitudeBand, query) -> imageMaybe
      }
    }
  }

  def grabImage(date: Date, key: ((Char, Char), Int)) = {
    preload(date, key)
    cache((zoneNumber, latitudeBand, (date, key._1._1, key._1._2, key._2)))
  }

  // def liveTiles(): Map[(Char, Char), (Extent, Promise[Option[Image]])] = {
  //   def isCloudy(key: (Char, Char))(elem: (Date, Int)) = {
  //     print(".")
  //     val (date, num) = elem
  //     preload(date, (key, num))
  //     val (_, cloudy, _) = cache((zoneNumber, latitudeBand, (date, key._1, key._2, num)))
  //     cloudy > params.cloudyThreshold
  //   }

  //   def findCloudless(key: (Char, Char)) = {
  //     val list = tilesAtKey(key)
  //     tilesAtKey += key -> list.dropWhile(isCloudy(key))
  //   }

  //   keyExtents.map { case (key, ex) => {
  //     val promise = Promise[Option[Image]]()
  //     Future {
  //       findCloudless(key)
  //       promise.success(tilesAtKey(key) match {
  //         case Nil => None
  //         case (date, num)::_ => Some(Await.result(grabImage(date, (key, num)).get.future, Duration.Inf))
  //       })
  //     }
  //     (key, (ex, promise))
  //   }}
  // }

  def keysAtPoint(x: Double, y: Double): Seq[(Char, Char)] = {
    keyExtents.toSeq.flatMap { case (key, ex) => if (ex.contains(x, y)) Some(key) else None }
  }
}
