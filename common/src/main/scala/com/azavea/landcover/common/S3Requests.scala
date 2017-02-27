package com.azavea.landcover.common

import geotrellis.proj4.CRS
import geotrellis.vector.Extent

import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
import com.amazonaws.regions.{Region, Regions}
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.{ListObjectsV2Request, ListObjectsV2Result}
import org.w3c.dom._

import geotrellis.proj4.CRS
import geotrellis.vector.{Extent, Geometry, Point, Polygon}

import spray.json._

import java.awt.Color
import java.awt.image.BufferedImage
import java.util.Date
import javax.imageio.ImageIO
import javax.xml.parsers.DocumentBuilderFactory
import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.util.Try

object S3Requests {

  case class SentinelCRS(crs: CRS)

  case class ProjectedGeom(crs: SentinelCRS, geom: Geometry)

  case class SentinelTileInfo (path: String,
                               utmZone: Int,
                               latitudeBand: Char,
                               key: (Char, Char),
                               date: Date,
                               number: Int,
                               crs: CRS,
                               coverageGeometry: Polygon,
                               tileExtent: Extent,
                               cloudyPercent: Double,
                               coveragePercent: Double) extends Serializable
  object SentinelTileInfo {
    def default(path: String) = {
      val toks = path.split("/")
      val zone = toks(1).toInt
      val band = toks(2).charAt(0)
      val key = (toks(3).charAt(0), toks(3).charAt(1))
      val sdf = new java.text.SimpleDateFormat("yyyy/M/d")
      val date = sdf.parse(s"${toks(4)}/${toks(5)}/${toks(6)}")
      val num = toks(7).toInt
      SentinelTileInfo (path, zone, band, key, date, num, CRS.fromEpsgCode(s"326$zone".toInt), Polygon(Point(0,0), Point(0,0), Point(0,0), Point(0,0)), Extent(0,0,0,0), 0.0/0.0, 0.0/0.0)
    }
  }

  object SentinelJsonProtocol extends DefaultJsonProtocol {
    implicit val crsFormat = new RootJsonFormat[SentinelCRS] {
      def read(json: JsValue): SentinelCRS = {
        val props = json.asJsObject.getFields("properties").apply(0)
        val crs = props.asJsObject.getFields("name").apply(0).convertTo[String]
        val epsgCode = crs.split(":").last.toInt
        SentinelCRS(CRS.fromEpsgCode(epsgCode))
      }

      def write(scrs: SentinelCRS): JsValue = throw new UnsupportedOperationException("Cannot write Sentinel-formatted CRS")
    }

    implicit val geomFormat = new RootJsonFormat[ProjectedGeom] {
      def write(pgeom: ProjectedGeom): JsValue = throw new UnsupportedOperationException("Cannot write projected geometry")

      def read(json: JsValue): ProjectedGeom = {
        val crs = json.asJsObject.getFields("crs").apply(0).convertTo[SentinelCRS]
        val geom: Geometry = json.asJsObject.getFields("type").apply(0).convertTo[String] match {
          case "Polygon" => {
            val coords = json.asJsObject.getFields("coordinates").apply(0).convertTo[Array[Array[List[Double]]]].apply(0)
            Polygon(coords.map{ arr => Point(arr(0), arr(1)) })
          }
          case "Point" => {
            val coord = json.asJsObject.getFields("coordinates").apply(0).convertTo[Array[Double]]
            Point(coord(0), coord(1))
          }
        }
        ProjectedGeom(crs, geom)
      }
    }

    implicit val tileInfoFormat = new RootJsonFormat[SentinelTileInfo] {
      def write(tileInfo: SentinelTileInfo): JsValue = throw new UnsupportedOperationException("Cannot write Sentinel tile info")

      def read(json: JsValue): SentinelTileInfo = {
        val jsobj = json.asJsObject
        val path = jsobj.getFields("path").apply(0).convertTo[String]
        val pathTokens = path.split("/")
        val sdf = new java.text.SimpleDateFormat("yyyy/M/d")
        val date = sdf.parse(s"${pathTokens(4)}/${pathTokens(5)}/${pathTokens(6)}")
        val utmZone = pathTokens(1).toInt
        val latitudeBand = pathTokens(2).charAt(0)
        val number = pathTokens(7).toInt
        val key = (pathTokens(3).charAt(0), pathTokens(3).charAt(1))
        val coverage = jsobj.getFields("tileDataGeometry").apply(0).convertTo[ProjectedGeom]
        val extent = jsobj.getFields("tileGeometry").apply(0).convertTo[ProjectedGeom]
        val cloudyPct = jsobj.getFields("cloudyPixelPercentage").apply(0).convertTo[Double]
        val coveragePct = jsobj.getFields("dataCoveragePercentage").apply(0).convertTo[Double]
        SentinelTileInfo(path,
                         utmZone,
                         latitudeBand,
                         key,
                         date,
                         number,
                         coverage.crs.crs,
                         coverage.geom.asInstanceOf[Polygon],
                         extent.geom.envelope,
                         cloudyPct,
                         coveragePct)
      }
    }
  }

  def getCloudyBounds(params: CommandLine.Params, zoneNumber: Int, latitudeBand: Char)(date: Date, key: ((Char, Char), Int)): (Double, Extent) = {
    val ((row, col), number) = key
    val ddisp = new java.text.SimpleDateFormat("yyyy/M/d")
    val dateStr = ddisp.format(date)
    val url = new java.net.URI(s"https://${params.s3Repository}.s3.amazonaws.com/tiles/${zoneNumber}/${latitudeBand}/${row}${col}/$dateStr/$number/metadata.xml").toURL

    val builder = DocumentBuilderFactory.newInstance.newDocumentBuilder 
    val doc = builder.parse(url.openStream)

    val ulx = doc.getElementsByTagName("ULX").item(0).getTextContent.toInt
    val uly = doc.getElementsByTagName("ULY").item(0).getTextContent.toInt
    val xstep = doc.getElementsByTagName("XDIM").item(0).getTextContent.toInt
    val ystep = doc.getElementsByTagName("YDIM").item(0).getTextContent.toInt
    val rows = doc.getElementsByTagName("NROWS").item(0).getTextContent.toInt
    val cols = doc.getElementsByTagName("NCOLS").item(0).getTextContent.toInt

    //(CRS.fromName(doc.getElementsByTagName("HORIZONTAL_CS_CODE").item(0).getTextContent),
    (doc.getElementsByTagName("CLOUDY_PIXEL_PERCENTAGE").item(0).getTextContent.toDouble,
     Extent(ulx.toDouble, (uly + ystep * cols).toDouble, (ulx + xstep * cols).toDouble, uly.toDouble))
  }

  def readSentinelTileInfo(params: CommandLine.Params, zoneNumber: Int, latitudeBand: Char)(date: Date, key: ((Char, Char), Int)): SentinelTileInfo = {
    import SentinelJsonProtocol._

    val ((row, col), number) = key
    val ddisp = new java.text.SimpleDateFormat("yyyy/M/d")
    val dateStr = ddisp.format(date)
    val url = new java.net.URI(s"https://${params.s3Repository}.s3.amazonaws.com/tiles/${zoneNumber}/${latitudeBand}/${row}${col}/$dateStr/$number/tileInfo.json").toURL
    val source = scala.io.Source.fromURL(url)
    val jsonString = source.mkString
    jsonString.parseJson.convertTo[SentinelTileInfo]
  }

  def populateCatalog(client: AmazonS3Client, params: CommandLine.Params, zoneNumber: Int, latitudeBand: Char)
      (keypair: (Char, Char), store: mutable.Map[Date, List[((Char, Char), Int, SentinelTileInfo)]]): 
      mutable.Map[Date, List[((Char, Char), Int, SentinelTileInfo)]] = 
  {
    val prefix = s"tiles/${zoneNumber}/${latitudeBand}/${keypair._1}${keypair._2}/"

    def recur(accum:String, depth: Int): Unit = {
      val objreq = (new ListObjectsV2Request).withBucketName(params.s3Repository)
                                             .withPrefix(prefix++accum)
                                             .withDelimiter("/")
      val query = client.listObjectsV2(objreq).getCommonPrefixes
      val results = query.map(_.split("/").last)

      // if (depth == 3) {
      //   print(s"${keypair._1}${keypair._2} ")
      // }

      if (depth == 0) {
        val sdf = new java.text.SimpleDateFormat("yyyy/MM/dd/")
        val date = sdf.parse(accum)
        results.par.foreach{
          i: String => {
            val tileInfo = Try(readSentinelTileInfo(params, zoneNumber, latitudeBand)(date, (keypair, i.toInt)))
            val ids = store getOrElse (date, Nil)
            store += date -> ((keypair, i.toInt, tileInfo.getOrElse({ 
              val path = s"$prefix$accum$i"
              println(s"Couldn't load $path/tileInfo.json") 
              SentinelTileInfo.default(path)})) :: ids)
          }
        }
      } else {
        results.par.foreach{x: String => recur(accum ++ x ++ "/", depth - 1)}
      }
    }

    recur("", 3)
    store
  }

  def getSentinelCoverage(params: CommandLine.Params, zoneNumber: Int, latitudeBand: Char): (Seq[(Char,Char)], mutable.Map[Date, List[((Char, Char), Int, SentinelTileInfo)]]) = {
    val baseDir = s"s3://${params.s3Repository}/tiles/${zoneNumber}/${latitudeBand}/"
    val region = Region.getRegion(Regions.fromName(params.s3Region))
    
    val cred = new DefaultAWSCredentialsProviderChain()
    val client = new AmazonS3Client(cred)
    client.setRegion(region)

    val objreq = (new ListObjectsV2Request).withBucketName(params.s3Repository)
                                           .withPrefix(s"tiles/${zoneNumber}/${latitudeBand}/")
                                           .withDelimiter("/")
    val s3objects = client.listObjectsV2(objreq)

    val prefixes = s3objects.getCommonPrefixes
    val toPairs = {s: String => (s.charAt(0), s.charAt(1))}

    val keys = prefixes.map{prefix => toPairs(prefix.split("/").last)}
    val result = (keys, keys.par.foldRight(mutable.Map.empty[Date, List[((Char, Char), Int, SentinelTileInfo)]])(populateCatalog(client, params, zoneNumber, latitudeBand)))
    //println
    result
  }

  def readPreviewImage(params: CommandLine.Params, zoneNumber: Int, latitudeBand: Char)(date: Date, key: ((Char, Char), Int)): BufferedImage = {
    val ((row, col), number) = key
    val ddisp = new java.text.SimpleDateFormat("yyyy/M/d")
    val dateStr = ddisp.format(date)
    val url = new java.net.URI(s"https://${params.s3Repository}.s3.amazonaws.com/tiles/${zoneNumber}/${latitudeBand}/${row}${col}/$dateStr/$number/preview.jpg").toURL

    println(s"Downloading image at $url")
    val rawImg = ImageIO.read(ImageIO.createImageInputStream(url.openStream))
    val bufImg = new BufferedImage(rawImg.getWidth, rawImg.getHeight, BufferedImage.TYPE_INT_ARGB)

    for ( x <- 0 until bufImg.getWidth ;
          y <- 0 until bufImg.getHeight ) {
      val argb: Int = rawImg.getRGB(x, y)
      if ((argb & 0x00FFFFFF) == 0x00FFFFFF) {
        bufImg.setRGB(x, y, 0x00FF0000)
      } else {
        bufImg.setRGB(x, y, argb)
      }
    }

    bufImg
  }

}
