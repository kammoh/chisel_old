/*
 Copyright (c) 2011, 2012, 2013, 2014 The Regents of the University of
 California (Regents). All Rights Reserved.  Redistribution and use in
 source and binary forms, with or without modification, are permitted
 provided that the following conditions are met:

    * Redistributions of source code must retain the above
      copyright notice, this list of conditions and the following
      two paragraphs of disclaimer.
    * Redistributions in binary form must reproduce the above
      copyright notice, this list of conditions and the following
      two paragraphs of disclaimer in the documentation and/or other materials
      provided with the distribution.
    * Neither the name of the Regents nor the names of its contributors
      may be used to endorse or promote products derived from this
      software without specific prior written permission.

 IN NO EVENT SHALL REGENTS BE LIABLE TO ANY PARTY FOR DIRECT, INDIRECT,
 SPECIAL, INCIDENTAL, OR CONSEQUENTIAL DAMAGES, INCLUDING LOST PROFITS,
 ARISING OUT OF THE USE OF THIS SOFTWARE AND ITS DOCUMENTATION, EVEN IF
 REGENTS HAS BEEN ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

 REGENTS SPECIFICALLY DISCLAIMS ANY WARRANTIES, INCLUDING, BUT NOT
 LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 A PARTICULAR PURPOSE. THE SOFTWARE AND ACCOMPANYING DOCUMENTATION, IF
 ANY, PROVIDED HEREUNDER IS PROVIDED "AS IS". REGENTS HAS NO OBLIGATION
 TO PROVIDE MAINTENANCE, SUPPORT, UPDATES, ENHANCEMENTS, OR
 MODIFICATIONS.
*/

package Chisel
import Chisel._
import scala.math._
import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable.HashMap
import scala.util.Random
import java.io.{File, IOException, InputStream, OutputStream, PrintStream}
import scala.sys.process._
import scala.io.Source._
import Literal._

case class Poke(val node: Node, val index: Int, val value: BigInt);

class Snapshot(val t: Int) {
  val pokes = new ArrayBuffer[Poke]()
}

class ManualTester[+T <: Module]
    (val c: T, val isT: Boolean = true) {
  var testIn:  InputStream  = null
  var testOut: OutputStream = null
  var testErr: InputStream  = null
  val sb = new StringBuilder()
  var delta = 0
  var t = 0
  var isTrace = isT

  /**
   * Waits until the emulator streams are ready. This is a dirty hack related
   * to the way Process works. TODO: FIXME.
   */
  def waitForStreams() = {
    var waited = 0
    while (testOut == null || testIn == null || testErr == null) {
      Thread.sleep(100)
      if (waited % 10 == 0 && waited > 30) {
        println("waiting for emulator process treams to be valid ...")
      }
    }
  }

  // TODO: MOVE TO SOMEWHERE COMMON TO BACKEND
  def ensureDir(dir: String): String = {
    val d = dir + (if (dir == "" || dir(dir.length-1) == '/') "" else "/")
    new File(d).mkdirs()
    d
  }
  def createOutputFile(name: String): java.io.FileWriter = {
    val baseDir = ensureDir(Driver.targetDir)
    new java.io.FileWriter(baseDir + name)
  }

  def puts(str: String) = {
    while (testOut == null) { Thread.sleep(100) }
    for (e <- str) testOut.write(e);
  }

  /**
   * Sends a command to the emulator and returns the reply.
   * The standard protocol treats a single line as a command, which always
   * returns a single line of reply.
   */
  def emulatorCmd(str: String): String = {
    // validate cmd
    if (str contains "\n") {
      System.err.print(s"emulatorCmd($str): command should not contain newline")
      return "error"
    }

    waitForStreams()

    // send command to emulator
    for (e <- str) testOut.write(e);
    testOut.write('\n');
    testOut.flush()

    // read output from emulator
    var c = testIn.read
    sb.clear()
    while (c != '\n' && c != -1) {
      if (c == 0) {
        Thread.sleep(100)
      }
      sb += c.toChar
      // Look for a "PRINT" command.
      if (sb.length == 6 && sb.startsWith("PRINT ")) {
        do {
          c = testIn.read
          sb += c.toChar
        } while (c != ' ')
        // Get the PRINT character count.
        val printCommand = """^PRINT (\d+) """.r
        val printCommand(nChars) = sb.toString
        sb.clear()
        for (i <- 0 until nChars.toInt) {
          c = testIn.read
          sb += c.toChar
        }
        System.out.print(sb.toString())
        sb.clear()
      }
      c   = testIn.read
    }

    // drain errors
    try {
      while(testErr.available() > 0) {
        System.err.print(Character.toChars(testErr.read()))
      }
    } catch {
      case e : IOException => testErr = null; println("ERR EXCEPTION")
    }

    if (sb == "error") {
      System.err.print(s"FAILED: emulatorCmd($str): returned error")
      ok = false
    }
    return sb.toString
  }

  def setClocks(clocks: HashMap[Clock, Int]) {
    var cmd = "set_clocks"
    for (clock <- Driver.clocks) {
      if (clock.srcClock == null) {
        val s = BigInt(clocks(clock)).toString(16)
        cmd = cmd + " " + s
      }
    }
    emulatorCmd(cmd)
    // TODO: check for errors in return
  }

  def dumpName(data: Node): String = {
    if (Driver.backend.isInstanceOf[FloBackend]) {
      data.name
    } else
      data.chiselName
  }

  def peekBits(data: Node, off: Int = -1): BigInt = {
    if (dumpName(data) == "") {
      println("Unable to peek data " + data)
      -1
    } else {
      var cmd = ""
      if (off != -1) {
        cmd = "mem_peek " + dumpName(data) + " " + off;
      } else {
        cmd = "wire_peek " + dumpName(data);
      }
      val s = emulatorCmd(cmd)
      val rv = toLitVal(s)
      if (isTrace) println("  PEEK " + dumpName(data) + " " + (if (off >= 0) (off + " ") else "") + "-> " + s)
      rv
    }
  }

  def signed_fix(dtype: Bits, rv: BigInt): BigInt = {
    val w = dtype.needWidth()
    dtype match {
      /* Any "signed" node */
      case _: SInt | _ : Flo | _: Dbl => (if(rv >= (BigInt(1) << w - 1)) (rv - (BigInt(1) << w)) else rv)
      /* anything else (i.e., UInt) */
      case _ => (rv)
    }
  }

  def peekAt[T <: Bits](data: Mem[T], off: Int): BigInt = {
    // signed_fix(data(1), peekBits(data, off))
    peekBits(data, off)
  }

  def peek(data: Bits): BigInt = {
    signed_fix(data, peekBits(data.getNode))
  }

  def peek(data: Aggregate /*, off: Int = -1 */): Array[BigInt] = {
    data.flatten.map(x => x._2).map(peek(_))
  }

  def reset(n: Int = 1) = {
    emulatorCmd("reset " + n)
    // TODO: check for errors in return
    if (isTrace) println("RESET " + n)
  }

  def doPokeBits(data: Node, x: BigInt, off: Int = -1): Unit = {
    if (dumpName(data) == "") {
      println("Unable to poke data " + data)
    } else {

      var cmd = ""
      if (off != -1) {
        cmd = "mem_poke " + dumpName(data) + " " + off;
      } else {
        cmd = "wire_poke " + dumpName(data);
      }
      // Ensure the sign is before the prefix.
      val radixPrefix = if (x < 0) " -0x" else " 0x"
      val xval = radixPrefix + x.abs.toString(16)
      cmd = cmd + xval
      if (isTrace) {
        println("  POKE " + dumpName(data) + " " + (if (off >= 0) (off + " ") else "") + "<- " + xval)
      }
      val rtn = emulatorCmd(cmd)
      if (rtn != "ok") {
        System.err.print(s"FAILED: poke(${dumpName(data)}) returned false")
        ok = false
      }
    }
  }

  def pokeBits(data: Node, x: BigInt, off: Int = -1): Unit = {
    doPokeBits(data, x, off)
  }

  def pokeAt[T <: Bits](data: Mem[T], x: BigInt, off: Int): Unit = {
    pokeBits(data, x, off)
  }

  def poke(data: Bits, x: BigInt): Unit = {
    pokeBits(data.getNode, x)
  }

  def poke(data: Aggregate, x: Array[BigInt]): Unit = {
    val kv = (data.flatten.map(x => x._2), x.reverse).zipped;
    for ((x, y) <- kv)
      poke(x, y)
  }

  def step(n: Int) = {
    val target = t + n
    val s = emulatorCmd("step " + n)
    delta += s.toInt
    if (isTrace) println("STEP " + n + " -> " + target)
    t += n
  }

  def int(x: Boolean): BigInt = if (x) 1 else 0
  def int(x: Int): BigInt = x
  def int(x: Bits): BigInt = x.litValue()

  var ok = true;
  var failureTime = -1

  def expect (good: Boolean, msg: String): Boolean = {
    if (isTrace)
      println(msg + " " + (if (good) "PASS" else "FAIL"))
    if (!good) { ok = false; if (failureTime == -1) failureTime = t; }
    good
  }

  def expect (data: Bits, expected: BigInt): Boolean = {
    val mask = BigInt((1 << data.needWidth) - 1)
    val got = peek(data)

    expect((got & mask) == (expected & mask),
       "EXPECT " + dumpName(data) + " <- " + got + " == " + expected)
  }

  def expect (data: Aggregate, expected: Array[BigInt]): Boolean = {
    val kv = (data.flatten.map(x => x._2), expected.reverse).zipped;
    var allGood = true
    for ((d, e) <- kv)
      allGood = expect(d, e) && allGood
    allGood
  }

  /* We need the following so scala doesn't use our "tolerant" Float version of expect.
   */
  def expect (data: Bits, expected: Int): Boolean = {
    expect(data, BigInt(expected))
  }
  def expect (data: Bits, expected: Long): Boolean = {
    expect(data, BigInt(expected))
  }

  /* Compare the floating point value of a node with an expected floating point value.
   * We will tolerate differences in the bottom bit.
   */
  def expect (data: Bits, expected: Float): Boolean = {
    val gotBits = peek(data).toInt
    val expectedBits = java.lang.Float.floatToIntBits(expected)
    var gotFLoat = java.lang.Float.intBitsToFloat(gotBits)
    var expectedFloat = expected
    if (gotFLoat != expectedFloat) {
      val gotDiff = gotBits - expectedBits
      // Do we have a single bit difference?
      if (abs(gotDiff) <= 1) {
        expectedFloat = gotFLoat
      }
    }
    expect(gotFLoat == expectedFloat,
       "EXPECT " + dumpName(data) + " <- " + gotFLoat + " == " + expectedFloat)
  }

  val rnd = if (Driver.testerSeedValid) new Random(Driver.testerSeed) else new Random()
  var process: Process = null

  def start(): Process = {
    val n = Driver.appendString(Some(c.name),Driver.chiselConfigClassName)
    val target = Driver.targetDir + "/" + n
    val cmd =
      (if (Driver.backend.isInstanceOf[FloBackend]) {
         val dir = Driver.backend.asInstanceOf[FloBackend].floDir
         val command = ArrayBuffer(dir + "fix-console", ":is-debug", "true", ":filename", target + ".hex", ":flo-filename", target + ".mwe.flo")
         if (Driver.isVCD) { command ++= ArrayBuffer(":is-vcd-dump", "true") }
         if (Driver.emitTempNodes) { command ++= ArrayBuffer(":emit-temp-nodes", "true") }
         command ++= ArrayBuffer(":target-dir", Driver.targetDir)
         command.mkString(" ")
      } else {
         target + (if (Driver.backend.isInstanceOf[VerilogBackend]) " -q +vcs+initreg+0 " else "")
      })
    println("SEED " + Driver.testerSeed)
    println("STARTING " + cmd)
    val processBuilder = Process(cmd)
    val pio = new ProcessIO(in => testOut = in, out => testIn = out, err => testErr = err)
    process = processBuilder.run(pio)
    waitForStreams()
    t = 0
    reset(5)
    // Skip vpd message
    if (Driver.backend.isInstanceOf[VerilogBackend] && Driver.isDebug) {
      var vpdmsg = testIn.read
      while (vpdmsg != '\n' && vpdmsg != -1)
        vpdmsg = testIn.read
    }
    process
  }

  def finish(): Boolean = {
    if (process != null) {
      emulatorCmd("quit")

      if (testOut != null) {
        testOut.flush()
        testOut.close()
      }
      if (testIn != null) {
        testIn.close()
      }
      if (testErr != null) {
        testErr.close()
      }

      process.destroy()
    }
    println("RAN " + t + " CYCLES " + (if (ok) "PASSED" else { "FAILED FIRST AT CYCLE " + failureTime }))
    ok
  }
}

class Tester[+T <: Module](c: T, isTrace: Boolean = true) extends ManualTester(c, isTrace) {
  start()
}

class MapTester[+T <: Module](c: T, val testNodes: Array[Node]) extends Tester(c, false) {
  def splitFlattenNodes(args: Seq[Node]): (Seq[Node], Seq[Node]) = {
    if (args.length == 0) {
      (Array[Node](), Array[Node]())
    } else {
      val testNodes = args.map(i => i.maybeFlatten).reduceLeft(_ ++ _).map(x => x.getNode);
      (c.keepInputs(testNodes), c.removeInputs(testNodes))
    }
  }
  val (ins, outs) = splitFlattenNodes(testNodes)
  val testInputNodes    = ins.toArray;
  val testNonInputNodes = outs.toArray
  def step(svars: HashMap[Node, Node],
           ovars: HashMap[Node, Node] = new HashMap[Node, Node],
           isTrace: Boolean = true): Boolean = {
    if (isTrace) { println("---"); println("INPUTS") }
    for (n <- testInputNodes) {
      val v = svars.getOrElse(n, null)
      val i = if (v == null) BigInt(0) else v.litValue() // TODO: WARN
      pokeBits(n, i)
    }
    if (isTrace) println("OUTPUTS")
    var isSame = true
    step(1)
    for (o <- testNonInputNodes) {
      val rv = peekBits(o)
      if (isTrace) println("  READ " + o + " = " + rv)
      if (!svars.contains(o)) {
        ovars(o) = Literal(rv)
      } else {
        val tv = svars(o).litValue()
        if (isTrace) println("  EXPECTED: " + o + " = " + tv)
        if (tv != rv) {
          isSame = false
          if (isTrace) println("  *** FAILURE ***")
        } else {
          if (isTrace) println("  SUCCESS")
        }
      }
    }
    isSame
  }
  var tests: () => Boolean = () => { println("DEFAULT TESTS"); true }
  def defTests(body: => Boolean) = body

}

