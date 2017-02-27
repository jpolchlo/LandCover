package com.azavea.landcover.demo

import com.azavea.landcover.common._
import geotrellis.vector.Extent

import java.awt.{BorderLayout, Color, Dimension, Graphics, GridLayout, Image}
import java.awt.event.{ActionEvent, ActionListener, InputEvent, KeyEvent, MouseEvent, MouseListener}
import java.io.{File, FileInputStream, FileOutputStream, ObjectInputStream, ObjectOutputStream}
import java.util.Date
import java.text.SimpleDateFormat
import javax.swing._
import scala.collection.mutable.{Map, Set}
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success}

object PREVIEW {
  val HEIGHT = 343
  val WIDTH = 343
}

object FULL {
  val HEIGHT = 10980
  val WIDTH = 10980
}

/*
class ImageTile(params: CommandLine.Params)(key: (Char, Char)) extends JPanel {

  implicit val ec = ExecutionContext.global
  var image: Image = null
  var loadAction: Future[Image] = null

  override def getPreferredSize() = new Dimension(PREVIEW.WIDTH, PREVIEW.HEIGHT)
  override def getMinimumSize() = getPreferredSize
  override def getMaximumSize() = getPreferredSize

  def clearTile() = {
    image = null
    repaint(0, 0, 0, PREVIEW.WIDTH, PREVIEW.HEIGHT)
  }

  def selectTile(date: Date, number: Int) = {
    loadAction = Future {
      val sdf = new SimpleDateFormat("MMM dd, yyyy")
      println(s"Downloading tile $number for ${sdf.format(date)}")
      image = null
      repaint(0, 0, 0, PREVIEW.WIDTH, PREVIEW.HEIGHT)
      image = S3Requests.readPreviewImage(params)(date, (key, number))
      image
    }

    loadAction onComplete {
      case Success(_) => {
        println(s"Download for tile ${key._1}${key._2} succeeded")
        repaint(0, 0, 0, PREVIEW.WIDTH, PREVIEW.HEIGHT)
      }
      case Failure(_) => {
        println(s"Download for tile ${key._1}${key._2} failed!")
        image = null
      }
    }
  }

  override def paintComponent(g: Graphics) = {
    super.paintComponent(g)
    if (image == null) {
      g.drawLine(0, 0, PREVIEW.WIDTH, PREVIEW.HEIGHT)
      g.drawLine(0, PREVIEW.HEIGHT, PREVIEW.WIDTH, 0)
      g.drawString(s"${key._1}${key._2}", PREVIEW.WIDTH/2, PREVIEW.HEIGHT/2)
    } else {
      g.drawImage(image, 0, 0, null)
    }
  }
  
}
*/

class TileDisplay(params: CommandLine.Params)(catalog: Catalog) extends JPanel {
  implicit val ec = ExecutionContext.global

  // private var date: Date = null
  // private var keys: Seq[((Char, Char), Int)] = null
  private var bndry: Extent = catalog.getBoundary
  private var images: Seq[(Extent, Image)] = Nil

  private val tiles = catalog.liveTiles //Map.empty[(Char, Char), (Extent, Promise[Option[Image]])]
  tiles.values.foreach { case (ex, promise) => {
    promise.future onSuccess { case _ =>
      val (x0, y0) = locationToPoint(ex.xmin, ex.ymin)
      val (x1, y1) = locationToPoint(ex.xmax, ex.ymax)
      repaint(0, x0, y0, x1 - x0, y0 - y1)
    }
  }}

  //loadLiveTiles

  def zoneBound() = {
    val (zone, bandChar) = catalog.getZone
    val zoneString = "%02d".format(zone)
    val crs = geotrellis.proj4.CRS.fromName(s"EPSG:326${zoneString}")
    val band = "CDEFGHJKLMNPQRSTUVWX".indexOf(bandChar) - 10
    val ymin = band.toDouble * 8.0
    val ymax = if (band == 9) { 84.0 } else { ((band + 1).toDouble * 8.0) }
    val (xmin, xmax) = {
      val x0 = (zone - 31).toDouble * 6.0
      (zone, bandChar) match {
        case (31, 'V') => (x0, x0 + 3)
        case (32, 'V') => (x0 - 3, x0 + 6)
        case (31, 'X') => (x0, x0 + 9)
        case (33, 'X') => (x0 - 3, x0 + 9)
        case (35, 'X') => (x0 - 3, x0 + 9)
        case (37, 'X') => (x0 - 3, x0 + 6)
        case _ => (x0, x0 + 6)
      }
    }
    val trans = geotrellis.proj4.Proj4Transform(geotrellis.proj4.LatLng, crs)
    val (left, bottom) = trans(xmin, ymin)
    val (right, top) = trans(xmax, ymax)
    Extent(left, bottom, right, top)
  }

  // def loadLiveTiles() = {
  //   catalog.liveTiles.foreach { case (ex, promise) => {
  //     promise.future onSuccess {
  //       case img => {
  //         this.synchronized {
  //           images = (ex, img) +: images
  //         }
  //         val (x0, y0) = locationToPoint(ex.xmin, ex.ymin)
  //         val (x1, y1) = locationToPoint(ex.xmax, ex.ymax)
  //         repaint(0, x0, y0, x1 - x0, y0 - y1)
  //       }
  //     }
  //   }}
  // }

  /*
  def setQuery(d: Date, ks: Seq[((Char, Char), Int)]) = {
    this.synchronized {
      date = d
      keys = ks
      //bndry = null
      images = Nil
    }

    keys.filter { case (_,n) => n == 0 }.foreach{ key: ((Char, Char), Int) => {
      val loadAction: Future[(Extent, Image)] = Future {
        val sdf = new SimpleDateFormat("MMM dd, yyyy")
        println(s"Downloading tile ${key._1._1}${key._1._2}(${key._2}) for ${sdf.format(date)}")
        (S3Requests.getBoundingExtent(params)(date, key)._2, S3Requests.readPreviewImage(params)(date, key))
      }

      loadAction onComplete {
        case Success((box, img)) => {
          println(s"Download for tile ${key._1._1}${key._1._2} succeeded")
          if (!bndry.intersects(box)) {
            println(s"Out of range")
          }
          this.synchronized {
            // if (bndry == null)
            //   bndry = box
            // else
            //   bndry = bndry.expandToInclude(box)
            images = (box, img) +: images
          }
          val dim = getPreferredSize
          setSize(dim)
          repaint(0, 0, 0, dim.width, dim.height)
        }
        case Failure(_) => {
          println(s"Download for tile ${key._1._1}${key._1._2} failed!")
        }
      }
      
    }}
  }
  */

  def clearTiles() = {
    this.synchronized {
      //date = null
      //keys = null
      //bndry = null
      images = Nil
    }
  }

  // override def getMinimumSize() = new Dimension(PREVIEW.WIDTH, PREVIEW.HEIGHT)
  // override def getMaximumSize() = {
  //   val ncols = allkeys.map(_._1).toSet.size
  //   val nrows = allkeys.map(_._2).toSet.size
  //   new Dimension(ncols * PREVIEW.WIDTH, nrows * PREVIEW.HEIGHT)
  // }
  // override def getPreferredSize() = {
  //   this.synchronized {
  //     if (bndry == null) {
  //       new Dimension(PREVIEW.WIDTH, PREVIEW.HEIGHT)
  //     } else {
  //       val pxwidth = (bndry.width / 320.12).toInt
  //       val pxheight = (bndry.height / 320.12).toInt
  //       new Dimension(pxwidth, pxheight)
  //     }
  //   }
  // }
  override def getMinimumSize() = getPreferredSize
  override def getMaximumSize() = getPreferredSize
  override def getPreferredSize() = {
    val pxwidth = (bndry.width / 320.12).toInt
    val pxheight = (bndry.height / 320.12).toInt
    new Dimension(pxwidth, pxheight)
  }

  def locationToPoint(x: Double, y: Double): (Int, Int) = {
    (((x - bndry.xmin)/(10 * FULL.WIDTH.toDouble / PREVIEW.WIDTH.toDouble)).toInt,
     ((bndry.ymax - y)/(10 * FULL.HEIGHT.toDouble / PREVIEW.HEIGHT.toDouble)).toInt)
  }

  def pointToLocation(px: Int, py: Int): (Double, Double) = {
    val x = px.toDouble / getWidth.toDouble
    val y = py.toDouble / getHeight.toDouble
    (bndry.xmin + (bndry.xmax - bndry.xmin) * x, bndry.ymin + (bndry.ymax - bndry.ymin) * y)
  }

  override def paintComponent(g: Graphics) = {
    //setBackground(Color.BLACK)
    val dim = getPreferredSize
    g.setColor(Color.WHITE)
    g.fillRect(0, 0, dim.width, dim.height)
    // this.synchronized {
    //   images.foreach{case (box, tile) => {
    //     val (px, py) = locationToPoint(box.xmin, box.ymax)
    //     g.drawImage(tile, px, py, null)
    //   }}
    // }
    tiles.foreach{ case (key, value) => {
      val (extent, promise) = value
      val (x0, y0) = locationToPoint(extent.xmin, extent.ymin)
      val (x1, y1) = locationToPoint(extent.xmax, extent.ymax)
      if (promise.isCompleted) {
        Await.result(promise.future, Duration(10, "millis")) match {
          case None => ()
          case Some(img) => g.drawImage(img, x0, y1, null)
        }
      } else {
        g.setColor(Color.RED)
        g.drawLine(x0, y0, x1, y0)
        g.drawLine(x1, y0, x1, y1)
        g.drawLine(x1, y1, x0, y1)
        g.drawLine(x0, y1, x0, y0)
        g.drawLine(x0, y0, x1, y1)
        g.drawLine(x0, y1, x1, y0)
      }
    }}
  }
}

// class CBEventHandler(dates: Seq[Date], tiles: Seq[ImageTile]) extends ActionListener {
//   def actionPerformed(evt: ActionEvent): Unit = {
//     val cb = evt.getSource.asInstanceOf[JComboBox[String]]
//     val item = cb.getSelectedIndex
//     if (item == 0) {
//       println("Clearing tiles")
//       tiles.foreach{ t => t.clearTile }
//     } else {
//       val date = dates(item - 1)
//       val sdf = new SimpleDateFormat("MMM dd, yyyy")
//       println(s"Selected tiles for ${sdf.format(date)}")
//       tiles.foreach{t: ImageTile => t.selectTile(date, 0) }
//     }
//   }
// }

/*
class CBEventHandler(dates: Seq[Date], catalog: Map[Date, List[((Char, Char), Int)]], display: TileDisplay) extends ActionListener {
  def actionPerformed(evt: ActionEvent): Unit = {
    val cb = evt.getSource.asInstanceOf[JComboBox[String]]
    val item = cb.getSelectedIndex
    if (item == 0) {
      println("Clearing tiles")
      display.clearTiles
    } else {
      val date = dates(item - 1)
      val sdf = new SimpleDateFormat("MMM dd, yyyy")
      println(s"Selected tiles for ${sdf.format(date)}")
      display.setQuery(date, catalog(date))
    }
  }
}
*/

class ClickHandler(tileDisplay: TileDisplay, catalog: Catalog) extends MouseListener {
  def mouseClicked(evt: MouseEvent): Unit = {
    val (x, y) = (evt.getX, evt.getY)
    println(s"Click at ${(x,y)} --> ${tileDisplay.pointToLocation(x, y)}")
  }
  def mouseEntered(evt: MouseEvent): Unit = ()
  def mouseExited(evt: MouseEvent): Unit = ()
  def mousePressed(evt: MouseEvent): Unit = ()
  def mouseReleased(evt: MouseEvent): Unit = ()
}

class AppWindow(params: CommandLine.Params)(catalog: Catalog) 
extends JFrame("Landcover Demo") {
  import JFrame._

  def updateCatalog(zone: Int, latitude: Char) = {
    val dialog = new ModalProgressDialog(this, s"Loading data for tile $zone$latitude", "Working...")
    val runnable = new java.lang.Runnable {
      var state = true
      def run() = {
        while(state) {
          dialog.tick
          java.lang.Thread.sleep(100)
        }
      }
      def kill() = { state = false }
    }
    new java.lang.Thread(dialog)
    val thread = new java.lang.Thread(runnable)
    thread.start
    catalog.resetZone(zone, latitude)
    runnable.kill
  }

  val window = this
  val basePanel = new JPanel
  // val boxlayout = new BoxLayout(basePanel, BoxLayout.Y_AXIS)
  basePanel setLayout(new BorderLayout)
  getContentPane add basePanel

  /*
  val availableDates = catalog.keys.toSeq.sorted
  val sdf = new SimpleDateFormat("MMM d, yyyy")
  val dates = new JComboBox(Array("<select date>") ++ availableDates.map(sdf.format(_)).toArray.asInstanceOf[Array[String]])
  basePanel add dates
  */

  // val rows = keys.map(_._1).toSet.toSeq.sorted
  // val cols = keys.map(_._2).toSet.toSeq.sorted.reverse
  // val tiles = for (c <- cols; r <- rows) yield {
  //   new ImageTile(params)((r,c))
  // }
  // dates.addActionListener(new CBEventHandler(availableDates, tiles))

  // val canvas = new JPanel {
  //   setLayout(new GridLayout(cols.size,rows.size))

  //   for (t <- tiles) {
  //     add(t)
  //   }

  //   override def getPreferredSize() = new Dimension(PREVIEW.HEIGHT * rows.size, PREVIEW.WIDTH * cols.size)
  //   override def getMinimumSize() = getPreferredSize
  //   override def getMaximumSize() = getPreferredSize
  // }

  val tileDisplay = new TileDisplay(params)(catalog)
  // dates.addActionListener(new CBEventHandler(availableDates, catalog, tileDisplay))
  tileDisplay.addMouseListener(new ClickHandler(tileDisplay, catalog))
  val scroller = new JScrollPane(tileDisplay)
  basePanel.add(BorderLayout.CENTER, scroller)

  pack

  val quitStroke = KeyStroke.getKeyStroke(KeyEvent.VK_Q, InputEvent.CTRL_DOWN_MASK);
  basePanel.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(quitStroke, "QUIT")
  basePanel.getActionMap.put("QUIT", new AbstractAction { def actionPerformed(event: ActionEvent) = window.dispose })

  setSize(800, 800)
  setVisible(true)
  setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE)
}

object Main {
  def main(args: Array[String]): Unit = {
    val params = CommandLine.parser.parse(args, CommandLine.Params()) match {
      case Some(p) => p
      case None => throw new Exception("Problem parsing command line options, see terminal output for details")
    }

    val zoneNumber = params.initialZone.getOrElse {
      val z = JOptionPane.showInputDialog("Zone number:").toInt
      if (z <= 0 || z > 60) {
        throw new IllegalArgumentException("UTM zone must be in the range 1 to 60")
      }
      z
    }
    val latitudeBand = params.initialLat.getOrElse {
      val lbStr = JOptionPane.showInputDialog("Latitude band:")
      if (lbStr == null || "BCDEFGHJKLMNPQRSTUVWX".indexOf(lbStr.charAt(0).toUpper) == -1) {
        throw new IllegalArgumentException("Latitude band must be a character in the range B-X (excl I and O)")
      }
      lbStr.charAt(0).toUpper
    }

    // val catfile = new java.io.File(s"catalog${params.zoneNumber}${params.latitudeBand}.obj")
    // val (keys, fullcatalog) = if (!catfile.exists || params.forceCatalogLoad) {
    //   println("Loading SENTINEL catalog from S3")
    //   val coverage = S3Requests.getSentinelCoverage(params)
    //   val oos = new ObjectOutputStream(new FileOutputStream(catfile))
    //   oos.writeObject(coverage._2)
    //   coverage
    // } else {
    //   println("Reading catalog from file")
    //   val ois = new ObjectInputStream(new FileInputStream(catfile))
    //   val cat = ois.readObject.asInstanceOf[Map[Date, List[((Char,Char),Int)]]]
    //   val k = Set.empty[(Char,Char)]
    //   cat.values.foreach{ l: List[((Char,Char),Int)] => l.foreach{ x: ((Char,Char),Int) => k += x._1 } }
    //   (k, cat)
    // }
    // val catalog = Map(fullcatalog.toSeq.filter{ case (_,l) => l.length > 2 }: _*)

    val catalog = new Catalog(params)(zoneNumber, latitudeBand)
    val win = new AppWindow(params)(catalog)
  }
}

