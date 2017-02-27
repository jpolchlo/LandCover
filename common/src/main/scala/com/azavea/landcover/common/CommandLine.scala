package com.azavea.landcover.common

object CommandLine {

  case class Params(outputFilename: String = "",
                    initialZone: Option[Int] = None,
                    initialLat: Option[Char] = None,
                    cloudyThreshold: Double = 0.30,
                    forceCatalogLoad: Boolean = false,
                    s3Repository: String = "sentinel-s2-l1c",
                    s3Region: String = "eu-central-1")

  val parser = new scopt.OptionParser[Params]("landcover-demo") {
    // for debugging; prevents exit from sbt console
    override def terminate(exitState: Either[String, Unit]): Unit = ()

    head("landcover-demo", "0.1")

    opt[Int]('z', "zone")
      .text("Initial zone number (from 1 to 60)")
      .action( (x, conf) => {
        if (0 < x && x <= 60) {
          conf.copy(initialZone = Some(x))
        } else {
          throw new IllegalArgumentException("UTM zone must be in the range 1 to 60")
        }
      } )

    opt[String]('b', "band")
      .text("Initial latitude band (one of CDEFGHJKLMNPQRSTUVWX)")
      .action( (x, conf) => {
        if ("CDEFGHJKLMNPQRSTUVWX".indexOf(x.charAt(0).toUpper) != -1) {
          conf.copy(initialLat = Some(x.charAt(0).toUpper))
        } else {
          throw new IllegalArgumentException("Latitude band must be a single character from the set CDEFGHJKLMNPQRSTUVWX")
        }
      } )

    opt[Double]("cloudy")
      .text("Maximum proportion of cloudy pixels (from 0 to 1) [default=0.30]")
      .action( (x, conf) => conf.copy(cloudyThreshold = x) )

    opt[String]("s3repository")
      .text("S3 repository name for SENTINEL-2 imagery [default=sentinel-s2-l1c]")
      .action( (x, conf) => conf.copy(s3Repository = x) )

    opt[String]("s3region")
      .text("S3 region name for SENTINEL-2 imagery [default=eu-central-1]")
      .action( (x, conf) => conf.copy(s3Repository = x) )

    opt[Boolean]("reload-catalog")
      .text("Forces download of fresh SENTINEL-2 image catalog [default=false]")
      .action( (x, conf) => conf.copy(forceCatalogLoad = x) )

    // arg[Int]("<zone number>")
    //   .text("UTM zone for region of interest")
    //   .action( (x, conf) => conf.copy(zoneNumber = x) )

    // arg[String]("<latitude band>")
    //   .text("Latitude band of region of interest")
    //   .action( (x, conf) => conf.copy(latitudeBand = x.charAt(0)) )

    arg[String]("<output filename>")
      .text("Name of the file")
      .action( (x, conf) => conf.copy(outputFilename = x) )
   }

}

