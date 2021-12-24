// See LICENSE.SiFive for license details.

package freechips.rocketchip.tile

import chisel3._

import freechips.rocketchip.config.Parameters

case class CustomCSR(id: Int, mask: BigInt, init: Option[BigInt])

object CustomCSR {
  def constant(id: Int, value: BigInt): CustomCSR = CustomCSR(id, BigInt(0), Some(value))
}

class CustomCSRIO(implicit p: Parameters) extends CoreBundle {
  val wen = Bool()
  val wdata = UInt(xLen.W)
  val value = UInt(xLen.W)
}

class CustomCSRs(implicit p: Parameters) extends CoreBundle {
  // Not all cores have these CSRs, but those that do should follow the same
  // numbering conventions.  So we list them here but default them to None.
  protected def bpmCSRId = 0x7c0
  protected def bpmCSR: Option[CustomCSR] = None

  protected def chickenCSRId = 0x7c1
  protected def chickenCSR: Option[CustomCSR] = None

  //yh+begin
  protected def baseCSRId = 0x430
  protected def baseCSR: Option[CustomCSR] = None

  protected def sizeCSRId = 0x431
  protected def sizeCSR: Option[CustomCSR] = None

  protected def fuzzCSRId = 0x432
  protected def fuzzCSR: Option[CustomCSR] = None

  protected def startAddrCSRId = 0x433
  protected def startAddrCSR: Option[CustomCSR] = None

  protected def endAddrCSRId = 0x434
  protected def endAddrCSR: Option[CustomCSR] = None

  protected def prevCSRId = 0x435
  protected def prevCSR: Option[CustomCSR] = None

  protected def brqFwdCntCSRId = 0x436
  protected def brqFwdCntCSR: Option[CustomCSR] = None

  protected def brqCommitCntCSRId = 0x437
  protected def brqCommitCntCSR: Option[CustomCSR] = None

  protected def cacheHitCntCSRId = 0x438
  protected def cacheHitCntCSR: Option[CustomCSR] = None

  protected def cacheMissCntCSRId = 0x439
  protected def cacheMissCntCSR: Option[CustomCSR] = None

  //protected def numCommittedInstCSRId = 0x43a
  //protected def numCommittedInstCSR: Option[CustomCSR] = None

  //protected def numUsesBrqInstCSRId = 0x43b
  //protected def numUsesBrqInstCSR: Option[CustomCSR] = None

  def baseAddrOfBitmap = getOrElse(baseCSR, _.value, UInt(xLen.W))
  def sizeOfBitmap     = getOrElse(sizeCSR, _.value, UInt(xLen.W))
  def enableBRQ        = getOrElse(fuzzCSR, _.value(0), false.B)
  def enableLBQ        = getOrElse(fuzzCSR, _.value(1), false.B)
  def flushLBQ         = getOrElse(fuzzCSR, _.value(2), false.B)
  def enableDebug      = getOrElse(fuzzCSR, _.value(3), false.B)
  def do_reset         = getOrElse(fuzzCSR, _.value(4), false.B)
  def startAddr        = getOrElse(startAddrCSR, _.value, UInt(xLen.W))
  def endAddr          = getOrElse(endAddrCSR, _.value, UInt(xLen.W))
  def prev  	         = getOrElse(prevCSR, _.value, UInt(xLen.W))
  def brqFwdCnt        = getOrElse(brqFwdCntCSR, _.value, UInt(xLen.W))
  def brqCommitCnt     = getOrElse(brqCommitCntCSR, _.value, UInt(xLen.W))
  def cacheHitCnt      = getOrElse(cacheHitCntCSR, _.value, UInt(xLen.W))
  def cacheMissCnt     = getOrElse(cacheMissCntCSR, _.value, UInt(xLen.W))
  //def numCommittedInst = getOrElse(numCommittedInstCSR, _.value, UInt(xLen.W))
  //def numUsesBrqInst   = getOrElse(numUsesBrqInstCSR, _.value, UInt(xLen.W))
  //yh+end

  // If you override this, you'll want to concatenate super.decls
  //yh-def decls: Seq[CustomCSR] = bpmCSR.toSeq ++ chickenCSR
  def decls: Seq[CustomCSR] = bpmCSR.toSeq ++ chickenCSR ++ baseCSR ++ sizeCSR ++
              fuzzCSR ++ startAddrCSR ++ endAddrCSR ++ prevCSR ++ brqFwdCntCSR ++
              brqCommitCntCSR ++ cacheHitCntCSR ++ cacheMissCntCSR
              //++ totalMissCntCSR ++ htMissCntCSR ++
              //numCommittedInstCSR ++ numUsesBrqInstCSR //yh+

  val csrs = Vec(decls.size, new CustomCSRIO)

  def flushBTB = getOrElse(bpmCSR, _.wen, false.B)
  def bpmStatic = getOrElse(bpmCSR, _.value(0), false.B)
  def disableDCacheClockGate = getOrElse(chickenCSR, _.value(0), true.B)
  def disableICacheClockGate = getOrElse(chickenCSR, _.value(1), true.B)
  def disableCoreClockGate = getOrElse(chickenCSR, _.value(2), true.B)

  protected def getByIdOrElse[T](id: Int, f: CustomCSRIO => T, alt: T): T = {
    val idx = decls.indexWhere(_.id == id)
    if (idx < 0) alt else f(csrs(idx))
  }

  protected def getOrElse[T](csr: Option[CustomCSR], f: CustomCSRIO => T, alt: T): T =
    csr.map(c => getByIdOrElse(c.id, f, alt)).getOrElse(alt)
}
