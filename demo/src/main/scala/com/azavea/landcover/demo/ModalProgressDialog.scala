package com.azavea.landcover.demo

import java.awt.{BorderLayout}
import javax.swing.{JDialog, JFrame, JLabel, JProgressBar, WindowConstants}

class ModalProgressDialog(parent: JFrame, name: String, info: String) extends JDialog(parent, name, true) with java.lang.Runnable {
  val progress = new JProgressBar
  progress.setIndeterminate(true)
  add(BorderLayout.NORTH, new JLabel(info))
  add(BorderLayout.CENTER, progress)
  setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE)
  setSize(300, 75)
  setLocationRelativeTo(parent)

  var counter: Int = 0

  def run() = {
    setVisible(true)
  }

  def tick() = {
    counter += 1
    progress.setValue(counter)
  }
}
