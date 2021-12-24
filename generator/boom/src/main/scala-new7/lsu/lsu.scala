//******************************************************************************
// Copyright (c) 2012 - 2018, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE and LICENSE.SiFive for license details.
//------------------------------------------------------------------------------

//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
// RISCV Out-of-Order Load/Store Unit
//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
//
// Load/Store Unit is made up of the Load-Address Queue, the Store-Address
// Queue, and the Store-Data queue (LAQ, SAQ, and SDQ).
//
// Stores are sent to memory at (well, after) commit, loads are executed
// optimstically ASAP.  If a misspeculation was discovered, the pipeline is
// cleared. Loads put to sleep are retried.  If a LoadAddr and StoreAddr match,
// the Load can receive its data by forwarding data out of the Store-Data
// Queue.
//
// Currently, loads are sent to memory immediately, and in parallel do an
// associative search of the SAQ, on entering the LSU. If a hit on the SAQ
// search, the memory request is killed on the next cycle, and if the SDQ entry
// is valid, the store data is forwarded to the load (delayed to match the
// load-use delay to delay with the write-port structural hazard). If the store
// data is not present, or it's only a partial match (SB->LH), the load is put
// to sleep in the LAQ.
//
// Memory ordering violations are detected by stores at their addr-gen time by
// associatively searching the LAQ for newer loads that have been issued to
// memory.
//
// The store queue contains both speculated and committed stores.
//
// Only one port to memory... loads and stores have to fight for it, West Side
// Story style.
//
// TODO:
//    - Add predicting structure for ordering failures
//    - currently won't STD forward if DMEM is busy
//    - ability to turn off things if VM is disabled
//    - reconsider port count of the wakeup, retry stuff

package boom.lsu

import chisel3._
import chisel3.util._

import freechips.rocketchip.config.Parameters
import freechips.rocketchip.rocket
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util.Str

import boom.common._
import boom.exu.{BrResolutionInfo, Exception, FuncUnitResp, CommitSignals, ExeUnitResp}
import boom.util.{BoolToChar, AgePriorityEncoder, IsKilledByBranch, GetNewBrMask, WrapInc, IsOlder, UpdateBrMask}

class LSUExeIO(implicit p: Parameters) extends BoomBundle()(p)
{
  // The "resp" of the maddrcalc is really a "req" to the LSU
  val req       = Flipped(new ValidIO(new FuncUnitResp(xLen)))
  // Send load data to regfiles
  val iresp    = new DecoupledIO(new boom.exu.ExeUnitResp(xLen))
  val fresp    = new DecoupledIO(new boom.exu.ExeUnitResp(xLen+1)) // TODO: Should this be fLen?
}

//yh+begin
class LSUHtConfig(implicit p: Parameters) extends BoomBundle()(p)
{
  val base          = UInt(coreMaxAddrBits.W)
  val size          = UInt(32.W)
  val enableBRQ     = Bool()
  val enableLBQ     = Bool()
  val flushLBQ      = Bool()
  val prev	        = UInt(32.W)
  val brqFwdCnt     = UInt(xLen.W)
  val brqCommitCnt  = UInt(xLen.W)
}
//yh+end

class BoomDCacheReq(implicit p: Parameters) extends BoomBundle()(p)
  with HasBoomUOP
{
  val addr  = UInt(coreMaxAddrBits.W)
  val data  = Bits(coreDataBits.W)
  val is_hella = Bool() // Is this the hellacache req? If so this is not tracked in LDQ or STQ
}

class BoomDCacheResp(implicit p: Parameters) extends BoomBundle()(p)
  with HasBoomUOP
{
  val data = Bits(coreDataBits.W)
  val is_hella = Bool()
}

class LSUDMemIO(implicit p: Parameters, edge: TLEdgeOut) extends BoomBundle()(p)
{
  // In LSU's dmem stage, send the request
  val req         = new DecoupledIO(Vec(memWidth, Valid(new BoomDCacheReq)))
  // In LSU's LCAM search stage, kill if order fail (or forwarding possible)
  val s1_kill     = Output(Vec(memWidth, Bool()))
  // Get a request any cycle
  val resp        = Flipped(Vec(memWidth, new ValidIO(new BoomDCacheResp)))
  // In our response stage, if we get a nack, we need to reexecute
  val nack        = Flipped(Vec(memWidth, new ValidIO(new BoomDCacheReq)))

  val brinfo       = Output(new BrResolutionInfo)
  val exception    = Output(Bool())
  val rob_pnr_idx  = Output(UInt(robAddrSz.W))
  val rob_head_idx = Output(UInt(robAddrSz.W))

  val release = Flipped(new DecoupledIO(new TLBundleC(edge.bundle)))

  // Clears prefetching MSHRs
  val force_order  = Output(Bool())
  val ordered     = Input(Bool())

  override def cloneType = new LSUDMemIO().asInstanceOf[this.type]
}

class LSUCoreIO(implicit p: Parameters) extends BoomBundle()(p)
{
  val exe = Vec(memWidth, new LSUExeIO)
  //yh+begin
  //val fuzz_exe = new LSUFuzzIO
  val lbqAddr = Output(Vec(numLbqEntries, UInt(coreMaxAddrBits.W)))
  val lbqHead = Output(UInt(xLen.W))
  val lbqStatus = Output(UInt(xLen.W))
  val brqPrev = Output(UInt(xLen.W))
  val brqFwdCnt = Output(UInt(xLen.W))
  val brqCommitCnt = Output(UInt(xLen.W))
  //yh+end

  val dis_uops    = Flipped(Vec(coreWidth, Valid(new MicroOp)))
  val dis_ldq_idx = Output(Vec(coreWidth, UInt(ldqAddrSz.W)))
  val dis_stq_idx = Output(Vec(coreWidth, UInt(stqAddrSz.W)))

  val ldq_full    = Output(Vec(coreWidth, Bool()))
  val stq_full    = Output(Vec(coreWidth, Bool()))

  //yh+begin
  //val dis_brq     = Input(Vec(coreWidth, new LSUBrqIO))
  val dis_brq_idx = Output(Vec(coreWidth, UInt(brqAddrSz.W)))
  val dis_lbq_idx = Output(Vec(coreWidth, UInt(lbqAddrSz.W)))
  val brq_full    = Output(Vec(coreWidth, Bool()))
  val ht_config  = Input(new LSUHtConfig)
  //yh+end

  val fp_stdata   = Flipped(Decoupled(new ExeUnitResp(fLen)))

  val commit      = Input(new CommitSignals)
  val commit_load_at_rob_head = Input(Bool())

  // Stores clear busy bit when stdata is received
  // memWidth for int, 1 for fp (to avoid back-pressure fpstdat)
  val clr_bsy         = Output(Vec(memWidth + 1, Valid(UInt(robAddrSz.W))))

  // Speculatively safe load (barring memory ordering failure)
  val clr_unsafe      = Output(Vec(memWidth, Valid(UInt(robAddrSz.W))))

  // Tell the DCache to clear prefetches/speculating misses
  val fence_dmem   = Input(Bool())

  // Speculatively tell the IQs that we'll get load data back next cycle
  val spec_ld_wakeup = Output(Valid(UInt(maxPregSz.W)))
  // Tell the IQs that the load we speculated last cycle was misspeculated
  val ld_miss      = Output(Bool())

  val brinfo       = Input(new BrResolutionInfo)
  val rob_pnr_idx  = Input(UInt(robAddrSz.W))
  val rob_head_idx = Input(UInt(robAddrSz.W))
  val exception    = Input(Bool())

  val fencei_rdy  = Output(Bool())

  val lxcpt       = Output(Valid(new Exception))

  val tsc_reg     = Input(UInt())
}

class LSUIO(implicit p: Parameters, edge: TLEdgeOut) extends BoomBundle()(p)
{
  val ptw   = new rocket.TLBPTWIO
  val core  = new LSUCoreIO
  val dmem  = new LSUDMemIO

  val hellacache = Flipped(new freechips.rocketchip.rocket.HellaCacheIO)
}

class LDQEntry(implicit p: Parameters) extends BoomBundle()(p)
    with HasBoomUOP
{
  val addr                = Valid(UInt(coreMaxAddrBits.W))
  val addr_is_virtual     = Bool() // Virtual address, we got a TLB miss
  val addr_is_uncacheable = Bool() // Uncacheable, wait until head of ROB to execute

  val executed            = Bool() // load sent to memory, reset by NACKs
  val execute_ignore      = Bool() // Ignore the next response we get from memory, we need to replay it
  val succeeded           = Bool() // Load send data back to core
  val order_fail          = Bool()
  val observed            = Bool()

  val st_dep_mask         = UInt(numStqEntries.W) // list of stores older than us
  val youngest_stq_idx    = UInt(stqAddrSz.W) // index of the oldest store younger than us

  val forward_std_val     = Bool()
  val forward_stq_idx     = UInt(stqAddrSz.W) // Which store did we get the store-load forward from?

  val debug_wb_data       = UInt(xLen.W)
}

class STQEntry(implicit p: Parameters) extends BoomBundle()(p)
   with HasBoomUOP
{
  val addr                = Valid(UInt(coreMaxAddrBits.W))
  val addr_is_virtual     = Bool() // Virtual address, we got a TLB miss
  val data                = Valid(UInt(xLen.W))

  val committed           = Bool() // committed by ROB
  val succeeded           = Bool() // D$ has ack'd this, we don't need to maintain this anymore

  val debug_wb_data       = UInt(xLen.W)
}

//yh+begin
class BRQEntry(implicit p: Parameters) extends BoomBundle()(p)
   with HasBoomUOP
{
  val addr                = Valid(UInt(coreMaxAddrBits.W))
  //val addr                = Valid(UInt(paddrBits.W))
  val data                = Valid(UInt(xLen.W))

  val executed            = Bool() // load sent to memory, reset by NACKs
  val committed           = Bool() // committed by ROB

  val state               = UInt(2.W)
  val hash                = Bits(16.W)
  val prev                = Bits(coreMaxAddrBits.W)
  val forwarded           = Bool()
}

class LBQEntry(implicit p: Parameters) extends BoomBundle()(p)
   with HasBoomUOP
{
  val addr                = Valid(UInt(coreMaxAddrBits.W))
  val taken               = Bool()
  val mispredict          = Bool()
}
//yh+end

class LSU(implicit p: Parameters, edge: TLEdgeOut) extends BoomModule()(p)
  with rocket.HasL1HellaCacheParameters
{
  val io = IO(new LSUIO)

  val s_init :: s_load :: s_store :: s_done :: Nil = Enum(4) //yh+

  val ldq = Reg(Vec(numLdqEntries, Valid(new LDQEntry)))
  val stq = Reg(Vec(numStqEntries, Valid(new STQEntry)))


  val ldq_head         = Reg(UInt(ldqAddrSz.W))
  val ldq_tail         = Reg(UInt(ldqAddrSz.W))
  val stq_head         = Reg(UInt(stqAddrSz.W)) // point to next store to clear from STQ (i.e., send to memory)
  val stq_tail         = Reg(UInt(stqAddrSz.W))
  val stq_commit_head  = Reg(UInt(stqAddrSz.W)) // point to next store to commit
  val stq_execute_head = Reg(UInt(stqAddrSz.W)) // point to next store to execute

  assert (stq(stq_execute_head).valid ||
          stq_head === stq_execute_head || stq_tail === stq_execute_head,
            "stq_execute_head got off track.")

  val h_ready :: h_s1 :: h_s2 :: h_s2_nack :: h_wait :: h_replay :: h_dead :: Nil = Enum(7)
  // s1 : do TLB, if success and not killed, fire request go to h_s2
  //      store s1_data to register
  //      if tlb miss, go to s2_nack
  //      if don't get TLB, go to s2_nack
  //      store tlb xcpt
  // s2 : If kill, go to dead
  //      If tlb xcpt, send tlb xcpt, go to dead
  // s2_nack : send nack, go to dead
  // wait : wait for response, if nack, go to replay
  // replay : refire request, use already translated address
  // dead : wait for response, ignore it
  val hella_state           = RegInit(h_ready)
  val hella_req             = Reg(new rocket.HellaCacheReq)
  val hella_data            = Reg(new rocket.HellaCacheWriteData)
  val hella_paddr           = Reg(UInt(paddrBits.W))
  val hella_xcpt            = Reg(new rocket.HellaCacheExceptions)


  val dtlb = Module(new NBDTLB(
    instruction = false, lgMaxSize = log2Ceil(coreDataBytes), rocket.TLBConfig(dcacheParams.nTLBEntries)))

  io.ptw <> dtlb.io.ptw


  val clear_store     = WireInit(false.B)
  val live_store_mask = RegInit(0.U(numStqEntries.W))
  var next_live_store_mask = Mux(clear_store, live_store_mask & ~(1.U << stq_head),
                                              live_store_mask)


  def widthMap[T <: Data](f: Int => T) = VecInit((0 until memWidth).map(f))


  //-------------------------------------------------------------
  //-------------------------------------------------------------
  // Enqueue new entries
  //-------------------------------------------------------------
  //-------------------------------------------------------------

  // This is a newer store than existing loads, so clear the bit in all the store dependency masks
  for (i <- 0 until numLdqEntries)
  {
    when (clear_store)
    {
      ldq(i).bits.st_dep_mask := ldq(i).bits.st_dep_mask & ~(1.U << stq_head)
    }
  }

  // Decode stage
  var ld_enq_idx = ldq_tail
  var st_enq_idx = stq_tail

  val stq_nonempty = (0 until numStqEntries).map{ i => stq(i).valid }.reduce(_||_) =/= 0.U

  var ldq_full = Bool()
  var stq_full = Bool()

  for (w <- 0 until coreWidth)
  {
    ldq_full = WrapInc(ld_enq_idx, numLdqEntries) === ldq_head
    io.core.ldq_full(w)    := ldq_full
    io.core.dis_ldq_idx(w) := ld_enq_idx

    stq_full = WrapInc(st_enq_idx, numStqEntries) === stq_head
    io.core.stq_full(w)    := stq_full
    io.core.dis_stq_idx(w) := st_enq_idx


    val dis_ld_val = io.core.dis_uops(w).valid && io.core.dis_uops(w).bits.uses_ldq && !io.core.dis_uops(w).bits.exception
    val dis_st_val = io.core.dis_uops(w).valid && io.core.dis_uops(w).bits.uses_stq && !io.core.dis_uops(w).bits.exception
    when (dis_ld_val)
    {
      ldq(ld_enq_idx).valid                := true.B
      ldq(ld_enq_idx).bits.uop             := io.core.dis_uops(w).bits
      ldq(ld_enq_idx).bits.youngest_stq_idx  := st_enq_idx
      ldq(ld_enq_idx).bits.st_dep_mask     := next_live_store_mask

      ldq(ld_enq_idx).bits.addr.valid      := false.B
      ldq(ld_enq_idx).bits.executed        := false.B
      ldq(ld_enq_idx).bits.execute_ignore  := false.B
      ldq(ld_enq_idx).bits.succeeded       := false.B
      ldq(ld_enq_idx).bits.order_fail      := false.B
      ldq(ld_enq_idx).bits.observed        := false.B
      ldq(ld_enq_idx).bits.forward_std_val := false.B

      assert (!io.core.dis_uops(w).bits.is_br_or_jmp, "LDQ is_br_or_jmp") //yh+
      assert (ld_enq_idx === io.core.dis_uops(w).bits.ldq_idx, "[lsu] mismatch enq load tag.")
      assert (!ldq(ld_enq_idx).valid, "[lsu] Enqueuing uop is overwriting ldq entries")
    }
      .elsewhen (dis_st_val)
    {
      stq(st_enq_idx).valid           := true.B
      stq(st_enq_idx).bits.uop        := io.core.dis_uops(w).bits
      stq(st_enq_idx).bits.addr.valid := false.B
      stq(st_enq_idx).bits.data.valid := false.B
      stq(st_enq_idx).bits.committed  := false.B
      stq(st_enq_idx).bits.succeeded  := false.B

      assert (!io.core.dis_uops(w).bits.is_br_or_jmp, "STQ is_br_or_jmp") //yh+
      assert (st_enq_idx === io.core.dis_uops(w).bits.stq_idx, "[lsu] mismatch enq store tag.")
      assert (!stq(st_enq_idx).valid, "[lsu] Enqueuing uop is overwriting stq entries")
    }

    ld_enq_idx = Mux(dis_ld_val, WrapInc(ld_enq_idx, numLdqEntries),
                                 ld_enq_idx)

    next_live_store_mask = Mux(dis_st_val, next_live_store_mask | (1.U << st_enq_idx),
                                           next_live_store_mask)
    st_enq_idx = Mux(dis_st_val, WrapInc(st_enq_idx, numStqEntries),
                                 st_enq_idx)

    assert(!(dis_ld_val && dis_st_val), "A UOP is trying to go into both the LDQ and the STQ")
  }

  ldq_tail := ld_enq_idx
  stq_tail := st_enq_idx


  //yh+begin *** Miscellaneous ***
  val regEnableBRQ     = Reg(Bool())
  val initBRQ          = Reg(Bool())	

  val regEnableLBQ     = Reg(Bool())
  val initLBQ          = Reg(Bool())	

  val fwdCnt           = RegInit(0.U(xLen.W))
  val commitCnt        = RegInit(0.U(xLen.W))

  val regBrqFwdCnt     = Reg(UInt(xLen.W))
  val regBrqCommitCnt  = Reg(UInt(xLen.W))

  val regPrevHash      = Reg(UInt(32.W))

  regEnableBRQ := io.core.ht_config.enableBRQ
  initBRQ := io.core.ht_config.enableBRQ & !regEnableBRQ

  regEnableLBQ := io.core.ht_config.enableLBQ
  initLBQ := io.core.ht_config.enableLBQ & !regEnableLBQ

  regBrqFwdCnt := io.core.ht_config.brqFwdCnt
  regBrqCommitCnt := io.core.ht_config.brqCommitCnt
  regPrevHash := io.core.ht_config.prev

  io.core.brqFwdCnt := fwdCnt
  io.core.brqCommitCnt := commitCnt

  //assert (brq(brq_execute_head).valid ||
  //        brq_head === brq_execute_head || brq_tail === brq_execute_head,
  //          "brq_execute_head got off track.")

  val bitmap_base = Reg(UInt(coreMaxAddrBits.W))
  val bitmap_size = Reg(UInt(32.W))
  val flushLBQ = Reg(Bool())

  bitmap_base := io.core.ht_config.base
  bitmap_size := io.core.ht_config.size
  flushLBQ := io.core.ht_config.flushLBQ

  //val fuzz_exe_req = WireInit(io.core.fuzz_exe.req)
  //val fuzz_exe_mispredict = WireInit(io.core.fuzz_exe.mispredict)
  //val fuzz_exe_taken = WireInit(io.core.fuzz_exe.taken)

  val brinfo = Reg(new BrResolutionInfo)
  brinfo := io.core.brinfo  

  //yh+end *** Miscellaneous ***

  //yh+begin *** BRQ ***
  val brq               = Reg(Vec(numBrqEntries, Valid(new BRQEntry)))
  val brq_head          = RegInit(0.U(brqAddrSz.W))
  val brq_tail          = RegInit(0.U(brqAddrSz.W))
  val brq_commit_head   = RegInit(0.U(brqAddrSz.W)) // point to next store to commit
  val brq_commit_hash   = RegInit(0.U(16.W))

  var br_enq_idx        = brq_tail
  var brq_full          = Bool()
  val brq_nonempty      = (0 until numBrqEntries).map{ i => brq(i).valid }.reduce(_||_) =/= 0.U

  val prev_hash         = RegInit(0.U(coreMaxAddrBits.W))
  var temp_prev_hash    = prev_hash

  // Dispatch
  for (w <- 0 until coreWidth) {
    brq_full = WrapInc(br_enq_idx, numBrqEntries) === brq_head
    io.core.brq_full(w)    := brq_full
    io.core.dis_brq_idx(w) := br_enq_idx

    when (brq_full) {
      printf("YH+ brq_full! br_enq_idx: %d brq_head: %d io.core.exception: %d\n", br_enq_idx, brq_head, io.core.exception)
    }

    val dis_br_val = regEnableBRQ && io.core.dis_uops(w).valid && io.core.dis_uops(w).bits.is_br_or_jmp &&
                      io.core.dis_uops(w).bits.uses_brq && !io.core.dis_uops(w).bits.exception

    when (dis_br_val) {
      brq(br_enq_idx).valid             := true.B
      brq(br_enq_idx).bits.state        := s_init  // state
      brq(br_enq_idx).bits.hash         := io.core.dis_uops(w).bits.hash
      brq(br_enq_idx).bits.prev         := temp_prev_hash
      brq(br_enq_idx).bits.uop          := io.core.dis_uops(w).bits
      brq(br_enq_idx).bits.addr.valid   := false.B
      brq(br_enq_idx).bits.data.valid   := false.B
      brq(br_enq_idx).bits.executed     := false.B
      brq(br_enq_idx).bits.committed    := false.B
      brq(br_enq_idx).bits.forwarded    := false.B

      printf("YH+ [%d] Enqueue BRQ Entry, br_enq_idx: %d brq_head: %d brq_tail: %d uopc: %d hash>>1: %d prev_hash: %d debug_pc: %x\n",
              io.core.tsc_reg, br_enq_idx, brq_head, brq_tail, io.core.dis_uops(w).bits.uopc,
              io.core.dis_uops(w).bits.hash >> 1,
              (io.core.dis_uops(w).bits.debug_pc ^ io.core.dis_uops(w).bits.hash) >> 1,
              io.core.dis_uops(w).bits.debug_pc)

      assert (io.core.dis_uops(w).bits.uses_brq, "[lsu] !uses_brq")
      assert (!brq(br_enq_idx).valid, "[lsu] Enqueuing uop is overwriting brq entries")
    }
    br_enq_idx = Mux(dis_br_val, WrapInc(br_enq_idx, numBrqEntries),
                                br_enq_idx)
    temp_prev_hash = Mux(dis_br_val, (io.core.dis_uops(w).bits.debug_pc ^ io.core.dis_uops(w).bits.hash) >> 1,
                                    temp_prev_hash)
  }

  // Commit
  var temp_brq_commit_head = brq_commit_head
  var temp_brq_commit_hash = brq_commit_hash

  for (w <- 0 until coreWidth) {
    val commit_br = regEnableBRQ && io.core.commit.valids(w) &&
                      io.core.commit.uops(w).uses_brq && io.core.commit.uops(w).is_br_or_jmp

    when (commit_br) {
      brq(io.core.commit.uops(w).brq_idx).bits.committed := true.B
    }

    temp_brq_commit_head = Mux(commit_br, WrapInc(temp_brq_commit_head, numBrqEntries),
                               temp_brq_commit_head)
    temp_brq_commit_hash = Mux(commit_br, brq(io.core.commit.uops(w).brq_idx).bits.hash,
                               temp_brq_commit_hash)
  }

  // Mask gen.
  val match_mask      = Wire(Vec(numBrqEntries, UInt(brqAddrSz.W)))
  val match_mask_sum  = Wire(UInt(brqAddrSz.W))

  for (i <- 0 until numBrqEntries) {
    val commit_match = brq(i).bits.addr.valid && brq(i).bits.committed &&
                        brq(i).bits.addr.bits === brq(brq_head).bits.addr.bits
                       
    match_mask(i) := 0.U

    when (i.U === brq_head || commit_match) {
      match_mask(i) := 1.U
      //printf("YH+ [%d] match_mask(%d): 1.U\n", io.core.tsc_reg, i.U)
    }
  }

  match_mask_sum := match_mask.reduce(_ + _)

  // Exe
  for (i <- 0 until numBrqEntries)
  {
    printf("YH+ [%d] brq(%d) valid: %d state: %d executed: %d committed: %d\n",
            io.core.tsc_reg, i.U, brq(i).valid, brq(i).bits.state, brq(i).bits.executed, brq(i).bits.committed)

    when (reset.asBool || initBRQ.asBool) {
      brq(i).valid           := false.B
      brq(i).bits.addr.valid := false.B
      brq(i).bits.data.valid := false.B
      brq(i).bits.uop        := NullMicroOp
      brq(i).bits.state      := s_init
    } .elsewhen (io.core.exception) {

      when (!brq(i).bits.committed) {
        brq(i).valid           := false.B
        brq(i).bits.addr.valid := false.B
        brq(i).bits.data.valid := false.B
      }
      .elsewhen (i.U === brq_head && brq(i).bits.state === s_load) {
        brq(i).bits.executed := false.B
      }
    } .elsewhen (brq(i).valid) {
      brq(i).bits.uop.br_mask := GetNewBrMask(io.core.brinfo, brq(i).bits.uop.br_mask)

      when (IsKilledByBranch(io.core.brinfo, brq(i).bits.uop)) {
        printf("YH+ [%d] Killing, brq_idx: %d\n", io.core.tsc_reg, i.U)

        brq(i).valid            := false.B
        brq(i).bits.addr.valid  := false.B

      } .elsewhen (io.core.brinfo.ht_valid && i.U === io.core.brinfo.brq_idx) {
      //} .elsewhen (brq(i).bits.state === s_init) {
        assert(brq(i).bits.state === s_init, "state =/= s_init")

        printf("YH+ [%d] BRQ S_INIT, brq_idx: %d brq_head: %d brq_tail: %d target: %x\n",
                io.core.tsc_reg, i.U, brq_head, brq_tail, io.core.brinfo.target)


        brq(i).bits.addr.valid          := true.B
        brq(i).bits.addr.bits           := bitmap_base |
                                            ((io.core.brinfo.target ^ brq(i).bits.hash ^
                                              brq(i).bits.prev) & bitmap_size)
        brq(i).bits.state               := s_load
        brq(i).bits.uop.uopc            := uopLD
        brq(i).bits.uop.mem_size        := 0.U
        brq(i).bits.uop.uses_ldq        := true.B
        brq(i).bits.uop.uses_stq        := false.B
        //brq(i).bits.uop.is_fuzz_ld      := true.B
        //brq(i).bits.uop.is_fuzz_st      := false.B
        brq(i).bits.uop.mem_cmd         := rocket.M_XRD

      } .elsewhen (brq(i).bits.state  === s_load) {
        val forwarded = brq(i).bits.committed && i.U =/= brq_head &&
                        brq(i).bits.addr.bits === brq(brq_head).bits.addr.bits

        when (forwarded) {
          printf("YH+ [%d] BRQ S_FWD, brq_idx: %d brq_head: %d brq_tail: %d\n",
                  io.core.tsc_reg, i.U, brq_head, brq_tail)

          brq(i).bits.state             := s_done
          brq(i).bits.forwarded         := true.B
        }
      } .elsewhen (brq(i).bits.state === s_store) {
        assert(i.U === brq_head)

      //} .elsewhen (i.U === brq_head && brq(i).bits.state === s_done) {
      } .elsewhen (i.U === brq_head && brq(i).bits.state === s_done && brq(i).bits.committed) { //yh+
        printf("YH+ [%d] BRQ S_DONE, brq_idx: %d, brq_head: %d brq_tail: %d\n",
                io.core.tsc_reg, i.U, brq_head, brq_tail)

        brq(i).valid           := false.B
        brq(i).bits.addr.valid := false.B
        brq(i).bits.data.valid := false.B
        brq(i).bits.committed  := false.B
      }
    }
  }

  // Clear TODO
  //val clear_br = brq(brq_head).valid && brq(brq_head).bits.state === s_done && !io.core.exception
  val clear_br = brq(brq_head).valid && brq(brq_head).bits.state === s_done && !io.core.exception && brq(brq_head).bits.committed //yh+

  when (reset.asBool || initBRQ) {
    brq_head := 0.U
    fwdCnt := regBrqFwdCnt
    commitCnt := regBrqCommitCnt
  } .elsewhen (clear_br) {
    brq_head := WrapInc(brq_head, numBrqEntries)

    when (brq(brq_head).bits.forwarded) {
      fwdCnt := fwdCnt + 1.U
    }

    commitCnt := commitCnt + 1.U

    printf("YH+ [%d] fwdCnt: %d commitCnt: %d\n", io.core.tsc_reg, fwdCnt, commitCnt)
  }

  //
  when (reset.asBool || initBRQ) {
    printf("YH+ [%d] reset occurred\n",
            io.core.tsc_reg)

    brq_tail := 0.U
    brq_commit_head  := 0.U
    brq_commit_hash := 0.U
    prev_hash := regPrevHash
  } .elsewhen (io.core.exception) {
    printf("YH+ [%d] exception occurred\n",
            io.core.tsc_reg)

    brq_tail := brq_commit_head
    brq_commit_head := temp_brq_commit_head
    brq_commit_hash := temp_brq_commit_hash
    prev_hash := temp_brq_commit_hash >> 1
  } .elsewhen (io.core.brinfo.valid && io.core.brinfo.mispredict && !io.core.exception) {
    var bidx = io.core.brinfo.brq_idx
    printf("YH+ [%d] Mispredicted!, brq_idx: %d\n", io.core.tsc_reg, io.core.brinfo.brq_idx)

    brq_commit_head := temp_brq_commit_head
    brq_commit_hash := temp_brq_commit_hash

    when (brq(bidx).valid && !IsKilledByBranch(io.core.brinfo, brq(bidx).bits.uop)) {
      printf("YH+ [%d] Not killed by branch, uopc: %d\n",
              io.core.tsc_reg, brq(bidx).bits.uop.uopc)

      brq_tail := WrapInc(io.core.brinfo.brq_idx, numBrqEntries)
      prev_hash := (io.core.brinfo.target ^ brq(bidx).bits.hash) >> 1
    } .otherwise {
      printf("YH+ [%d] Killed by branch, uopc: %d\n",
              io.core.tsc_reg, brq(bidx).bits.uop.uopc)

      brq_tail := io.core.brinfo.brq_idx
      prev_hash := brq(bidx).bits.prev
    }
  } .otherwise {
    brq_commit_head := temp_brq_commit_head
    brq_commit_hash := temp_brq_commit_hash
    brq_tail := br_enq_idx
    prev_hash := temp_prev_hash
  }

  io.core.brqPrev := prev_hash
  //yh+end *** BRQ ***

  //yh+begin *** LBQ ***
  val lbq               = Reg(Vec(numLbqEntries, Valid(new LBQEntry)))
  val lbq_head          = RegInit(0.U(lbqAddrSz.W))
  val lbq_tail          = RegInit(0.U(lbqAddrSz.W))

  var lb_enq_idx        = lbq_tail
  var temp_lbq_head     = lbq_head

  // Dispatch
  for (w <- 0 until coreWidth)
  {
    io.core.dis_lbq_idx(w) := lb_enq_idx
    //io.core.dis_lbq_idx(w) := br_enq_idx

    val dis_lb_val = regEnableLBQ && io.core.dis_uops(w).valid && io.core.dis_uops(w).bits.is_br_or_jmp &&
                      io.core.dis_uops(w).bits.uses_brq && !io.core.dis_uops(w).bits.exception
                      //io.core.dis_uops(w).bits.uses_brq && !io.core.dis_uops(w).bits.exception && !brq_full

    when (dis_lb_val)
    {
      lbq(lb_enq_idx).valid             := true.B
      lbq(lb_enq_idx).bits.uop          := io.core.dis_uops(w).bits
    }

    temp_lbq_head = Mux(dis_lb_val && WrapInc(lb_enq_idx, numLbqEntries) === temp_lbq_head, WrapInc(temp_lbq_head, numLbqEntries),
                          temp_lbq_head)

    lb_enq_idx = Mux(dis_lb_val, WrapInc(lb_enq_idx, numLbqEntries),
                                lb_enq_idx)
  }

  // Exe
  for (i <- 0 until numLbqEntries)
  {
    val exe_lb_val = io.core.brinfo.ht_valid && i.U === io.core.brinfo.lbq_idx
    //val exe_lb_val = io.core.brinfo.ht_valid && i.U === io.core.brinfo.lbq_idx && !io.core.brinfo.brq_full

    when (reset.asBool || io.core.exception || initLBQ)
    {
      lbq(i).valid           := false.B
      lbq(i).bits.addr.valid := false.B
      lbq(i).bits.taken      := false.B
      lbq(i).bits.mispredict := false.B
    } .elsewhen (lbq(i).valid) {
      lbq(i).bits.uop.br_mask := GetNewBrMask(io.core.brinfo, lbq(i).bits.uop.br_mask)

      when (IsKilledByBranch(io.core.brinfo, lbq(i).bits.uop)) {
        printf("YH+ [%d] Killing, lbq_idx: %d\n", io.core.tsc_reg, i.U)
        lbq(i).valid            := false.B
      } .elsewhen (exe_lb_val) {
        lbq(i).bits.addr.bits   := io.core.brinfo.target
        lbq(i).bits.mispredict  := io.core.brinfo.mispredict
        lbq(i).bits.taken       := io.core.brinfo.taken
      }
    }
  }

  when (reset.asBool || io.core.exception || initLBQ)
  {
    lbq_head := 0.U
    lbq_tail := 0.U
  } .elsewhen (io.core.brinfo.valid && io.core.brinfo.mispredict && !io.core.exception) {
    var lidx = io.core.brinfo.lbq_idx

    lbq_tail := Mux(lbq(lidx).valid && !IsKilledByBranch(io.core.brinfo, lbq(lidx).bits.uop),
                    WrapInc(io.core.brinfo.lbq_idx, numLbqEntries),
                    io.core.brinfo.lbq_idx)
  } .otherwise {
    lbq_head := temp_lbq_head
    lbq_tail := lb_enq_idx
  }

  io.core.lbqHead := lbq_head

  for (i <- 0 until numLbqEntries)
  {
    io.core.lbqAddr(i)        := lbq(i).bits.addr.bits
    //io.core.lbqTaken(i)       := lbq(lbq_head+i.U).bits.taken
    //io.core.lbqMispredict(i)  := lbq(lbq_head+i.U).bits.mispredict
    //io.core.lbqAddr(i)        := lbq(lbq_head+i.U).bits.addr.bits
    //io.core.lbqTaken(i)       := lbq(lbq_head+i.U).bits.taken
    //io.core.lbqMispredict(i)  := lbq(lbq_head+i.U).bits.mispredict
    //printf("YH+ [%d] i: %d io.core.lbqAddr: %d lbq_head: %d lbq(lbq_head+%d).bits.addr: %d\n",
    //        io.core.tsc_reg, i.U, io.core.lbqAddr(i), lbq_head, i.U, lbq(lbq_head+i.U).bits.addr.bits)
  }

  io.core.lbqStatus := Cat(
                lbq(31.U).bits.taken, lbq(30.U).bits.taken, lbq(29.U).bits.taken, lbq(28.U).bits.taken,
                lbq(27.U).bits.taken, lbq(26.U).bits.taken, lbq(25.U).bits.taken, lbq(24.U).bits.taken,
                lbq(23.U).bits.taken, lbq(22.U).bits.taken, lbq(21.U).bits.taken, lbq(20.U).bits.taken,
                lbq(19.U).bits.taken, lbq(18.U).bits.taken, lbq(17.U).bits.taken, lbq(16.U).bits.taken,
                lbq(15.U).bits.taken, lbq(14.U).bits.taken, lbq(13.U).bits.taken, lbq(12.U).bits.taken,
                lbq(11.U).bits.taken, lbq(10.U).bits.taken, lbq(9.U).bits.taken, lbq(8.U).bits.taken,
                lbq(7.U).bits.taken, lbq(6.U).bits.taken, lbq(5.U).bits.taken, lbq(4.U).bits.taken,
                lbq(3.U).bits.taken, lbq(2.U).bits.taken, lbq(1.U).bits.taken, lbq(0.U).bits.taken,
                lbq(31.U).bits.mispredict, lbq(30.U).bits.mispredict, lbq(29.U).bits.mispredict, lbq(28.U).bits.mispredict,
                lbq(27.U).bits.mispredict, lbq(26.U).bits.mispredict, lbq(25.U).bits.mispredict, lbq(24.U).bits.mispredict,
                lbq(23.U).bits.mispredict, lbq(22.U).bits.mispredict, lbq(21.U).bits.mispredict, lbq(20.U).bits.mispredict,
                lbq(19.U).bits.mispredict, lbq(18.U).bits.mispredict, lbq(17.U).bits.mispredict, lbq(16.U).bits.mispredict,
                lbq(15.U).bits.mispredict, lbq(14.U).bits.mispredict, lbq(13.U).bits.mispredict, lbq(12.U).bits.mispredict,
                lbq(11.U).bits.mispredict, lbq(10.U).bits.mispredict, lbq(9.U).bits.mispredict, lbq(8.U).bits.mispredict,
                lbq(7.U).bits.mispredict, lbq(6.U).bits.mispredict, lbq(5.U).bits.mispredict, lbq(4.U).bits.mispredict,
                lbq(3.U).bits.mispredict, lbq(2.U).bits.mispredict, lbq(1.U).bits.mispredict, lbq(0.U).bits.mispredict).asUInt

  //io.core.lbqStatus := Cat(
  //              lbq(lbq_head+31.U).bits.taken, lbq(lbq_head+30.U).bits.taken, lbq(lbq_head+29.U).bits.taken, lbq(lbq_head+28.U).bits.taken,
  //              lbq(lbq_head+27.U).bits.taken, lbq(lbq_head+26.U).bits.taken, lbq(lbq_head+25.U).bits.taken, lbq(lbq_head+24.U).bits.taken,
  //              lbq(lbq_head+23.U).bits.taken, lbq(lbq_head+22.U).bits.taken, lbq(lbq_head+21.U).bits.taken, lbq(lbq_head+20.U).bits.taken,
  //              lbq(lbq_head+19.U).bits.taken, lbq(lbq_head+18.U).bits.taken, lbq(lbq_head+17.U).bits.taken, lbq(lbq_head+16.U).bits.taken,
  //              lbq(lbq_head+15.U).bits.taken, lbq(lbq_head+14.U).bits.taken, lbq(lbq_head+13.U).bits.taken, lbq(lbq_head+12.U).bits.taken,
  //              lbq(lbq_head+11.U).bits.taken, lbq(lbq_head+10.U).bits.taken, lbq(lbq_head+9.U).bits.taken, lbq(lbq_head+8.U).bits.taken,
  //              lbq(lbq_head+7.U).bits.taken, lbq(lbq_head+6.U).bits.taken, lbq(lbq_head+5.U).bits.taken, lbq(lbq_head+4.U).bits.taken,
  //              lbq(lbq_head+3.U).bits.taken, lbq(lbq_head+2.U).bits.taken, lbq(lbq_head+1.U).bits.taken, lbq(lbq_head+0.U).bits.taken,
  //              lbq(lbq_head+31.U).bits.mispredict, lbq(lbq_head+30.U).bits.mispredict, lbq(lbq_head+29.U).bits.mispredict, lbq(lbq_head+28.U).bits.mispredict,
  //              lbq(lbq_head+27.U).bits.mispredict, lbq(lbq_head+26.U).bits.mispredict, lbq(lbq_head+25.U).bits.mispredict, lbq(lbq_head+24.U).bits.mispredict,
  //              lbq(lbq_head+23.U).bits.mispredict, lbq(lbq_head+22.U).bits.mispredict, lbq(lbq_head+21.U).bits.mispredict, lbq(lbq_head+20.U).bits.mispredict,
  //              lbq(lbq_head+19.U).bits.mispredict, lbq(lbq_head+18.U).bits.mispredict, lbq(lbq_head+17.U).bits.mispredict, lbq(lbq_head+16.U).bits.mispredict,
  //              lbq(lbq_head+15.U).bits.mispredict, lbq(lbq_head+14.U).bits.mispredict, lbq(lbq_head+13.U).bits.mispredict, lbq(lbq_head+12.U).bits.mispredict,
  //              lbq(lbq_head+11.U).bits.mispredict, lbq(lbq_head+10.U).bits.mispredict, lbq(lbq_head+9.U).bits.mispredict, lbq(lbq_head+8.U).bits.mispredict,
  //              lbq(lbq_head+7.U).bits.mispredict, lbq(lbq_head+6.U).bits.mispredict, lbq(lbq_head+5.U).bits.mispredict, lbq(lbq_head+4.U).bits.mispredict,
  //              lbq(lbq_head+3.U).bits.mispredict, lbq(lbq_head+2.U).bits.mispredict, lbq(lbq_head+1.U).bits.mispredict, lbq(lbq_head+0.U).bits.mispredict).asUInt
  //yh+end *** LBQ ***


  //printf("YH+ [%d] stq_head: %d stq_execute_head: %d stq_commit_head: %d is_fence: %d ordered: %d\n",
  //        io.core.tsc_reg, stq_head, stq_execute_head, stq_commit_head, stq(stq_head).bits.uop.is_fence, io.dmem.ordered)

  //for (i <- 0 until numStqEntries)
  //{
  //  printf("YH+ [%d] stq(%d) valid: %d succeeded: %d committed: %d\n",
  //          io.core.tsc_reg, i.U, stq(i).valid, stq(i).bits.succeeded, stq(i).bits.committed)
  //}

  //for (i <- 0 until numLdqEntries)
  //{
  //  printf("YH+ [%d] ldq(%d) valid: %d executed: %d\n",
  //          io.core.tsc_reg, i.U, ldq(i).valid, ldq(i).bits.executed)
  //}



  io.dmem.force_order   := io.core.fence_dmem
  io.core.fencei_rdy    := !stq_nonempty && io.dmem.ordered
  //io.core.fencei_rdy    := !stq_nonempty && !brq_nonempty && io.dmem.ordered //yh+

  printf("YH+ [%d] io.core.fencei_rdy: %d stq_nonempty: %d brq_nonempty: %d\n",
          io.core.tsc_reg, io.core.fencei_rdy, stq_nonempty, brq_nonempty)
  printf("YH+ [%d] brq_head: %d brq_tail: %d brq_commit_head: %d\n",
          io.core.tsc_reg, brq_head, brq_tail, brq_commit_head)


  //-------------------------------------------------------------
  //-------------------------------------------------------------
  // Execute stage (access TLB, send requests to Memory)
  //-------------------------------------------------------------
  //-------------------------------------------------------------

  // We can only report 1 exception per cycle.
  // Just be sure to report the youngest one
  val mem_xcpt_valid  = Wire(Bool())
  val mem_xcpt_cause  = Wire(UInt())
  val mem_xcpt_uop    = Wire(new MicroOp)
  val mem_xcpt_vaddr  = Wire(UInt())


  //---------------------------------------
  // Can-fire logic and wakeup/retry select
  //
  // First we determine what operations are waiting to execute.
  // These are the "can_fire"/"will_fire" signals

  val will_fire_load_incoming  = Wire(Vec(memWidth, Bool()))
  val will_fire_stad_incoming  = Wire(Vec(memWidth, Bool()))
  val will_fire_sta_incoming   = Wire(Vec(memWidth, Bool()))
  val will_fire_std_incoming   = Wire(Vec(memWidth, Bool()))
  val will_fire_sfence         = Wire(Vec(memWidth, Bool()))
  val will_fire_hella_incoming = Wire(Vec(memWidth, Bool()))
  val will_fire_hella_wakeup   = Wire(Vec(memWidth, Bool()))
  val will_fire_release        = Wire(Vec(memWidth, Bool()))
  val will_fire_load_retry     = Wire(Vec(memWidth, Bool()))
  val will_fire_sta_retry      = Wire(Vec(memWidth, Bool()))
  val will_fire_store_commit   = Wire(Vec(memWidth, Bool()))
  val will_fire_load_wakeup    = Wire(Vec(memWidth, Bool()))
  val will_fire_brq_load       = Wire(Vec(memWidth, Bool())) //yh+
  val will_fire_brq_store      = Wire(Vec(memWidth, Bool())) //yh+

  val exe_req = WireInit(VecInit(io.core.exe.map(_.req)))
  // Sfence goes through all pipes
  for (i <- 0 until memWidth) {
    when (io.core.exe(i).req.bits.sfence.valid) {
      exe_req := VecInit(Seq.fill(memWidth) { io.core.exe(i).req })
    }
  }

  // -------------------------------
  // Assorted signals for scheduling

  // Don't wakeup a load if we just sent it last cycle or two cycles ago
  val block_load_mask    = WireInit(VecInit((0 until numLdqEntries).map(x=>false.B)))
  val p1_block_load_mask = RegNext(block_load_mask)
  val p2_block_load_mask = RegNext(p1_block_load_mask)

  // The store at the commit head needs the DCache to appear ordered
  // Delay firing load wakeups and retries now
  val store_needs_order = WireInit(false.B)

  val ldq_incoming_idx = widthMap(i => exe_req(i).bits.uop.ldq_idx)
  val ldq_incoming_e   = widthMap(i => ldq(ldq_incoming_idx(i)))

  val stq_incoming_idx = widthMap(i => exe_req(i).bits.uop.stq_idx)
  val stq_incoming_e   = widthMap(i => stq(stq_incoming_idx(i)))

  val ldq_retry_idx = RegNext(AgePriorityEncoder((0 until numLdqEntries).map(i => {
    val e = ldq(i).bits
    val block = block_load_mask(i) || p1_block_load_mask(i)
    e.addr.valid && e.addr_is_virtual && !block
  }), ldq_head))
  val ldq_retry_e            = ldq(ldq_retry_idx)

  val stq_retry_idx = RegNext(AgePriorityEncoder((0 until numStqEntries).map(i => {
    val e = stq(i).bits
    e.addr.valid && e.addr_is_virtual
  }), stq_commit_head))
  val stq_retry_e   = stq(stq_retry_idx)

  val stq_commit_e  = stq(stq_execute_head)

  val ldq_wakeup_idx = RegNext(AgePriorityEncoder((0 until numLdqEntries).map(i=> {
    val e = ldq(i).bits
    val block = block_load_mask(i) || p1_block_load_mask(i)
    e.addr.valid && !e.executed && !e.succeeded && !e.addr_is_virtual && !block
  }), ldq_head))
  val ldq_wakeup_e   = ldq(ldq_wakeup_idx)

  // -----------------------
  // Determine what can fire

  //yh+begin
  val brq_exec_e = brq(brq_head)

  val can_fire_brq_load = widthMap(w => brq_exec_e.valid &&
                                    brq_exec_e.bits.state === s_load &&
                                    !brq_exec_e.bits.executed &&
                                    !io.core.exception &&
                                    (w == 0).B)

  val can_fire_brq_store = widthMap(w => brq_exec_e.valid &&
                                    brq_exec_e.bits.state === s_store &&
                                    brq_exec_e.bits.committed &&
                                    !brq_exec_e.bits.executed &&
                                    !io.core.exception &&
                                    (w == 0).B)

  printf("YH+ [%d] brq_head: %d brq_tail: %d valid: %d state: %d executed: %d committed: %d\n",
          io.core.tsc_reg, brq_head, brq_tail, brq_exec_e.valid, brq_exec_e.bits.state,
          brq_exec_e.bits.executed.asUInt, brq_exec_e.bits.committed.asUInt)
  printf("YH+ [%d] can_fire_brq_load: %d can_fire_brq_store: %d\n",
          io.core.tsc_reg, can_fire_brq_load.asUInt, can_fire_brq_store.asUInt)
  //yh+end


  // Can we fire a incoming load
  val can_fire_load_incoming = widthMap(w => exe_req(w).valid && exe_req(w).bits.uop.ctrl.is_load)

  // Can we fire an incoming store addrgen + store datagen
  val can_fire_stad_incoming = widthMap(w => exe_req(w).valid && exe_req(w).bits.uop.ctrl.is_sta
                                                              && exe_req(w).bits.uop.ctrl.is_std)

  // Can we fire an incoming store addrgen
  val can_fire_sta_incoming  = widthMap(w => exe_req(w).valid && exe_req(w).bits.uop.ctrl.is_sta
                                                              && !exe_req(w).bits.uop.ctrl.is_std)

  // Can we fire an incoming store datagen
  val can_fire_std_incoming  = widthMap(w => exe_req(w).valid && exe_req(w).bits.uop.ctrl.is_std
                                                              && !exe_req(w).bits.uop.ctrl.is_sta)

  // Can we fire an incoming sfence
  val can_fire_sfence        = widthMap(w => exe_req(w).valid && exe_req(w).bits.sfence.valid)

  // Can we fire a request from dcache to release a line
  // This needs to go through LDQ search to mark loads as dangerous
  val can_fire_release       = widthMap(w => (w == memWidth-1).B && io.dmem.release.valid)
  io.dmem.release.ready     := will_fire_release.reduce(_||_)

  // Can we retry a load that missed in the TLB
  val can_fire_load_retry    = widthMap(w =>
                               ( ldq_retry_e.valid                            &&
                                 ldq_retry_e.bits.addr.valid                  &&
                                 ldq_retry_e.bits.addr_is_virtual             &&
                                !p1_block_load_mask(ldq_retry_idx)            &&
                                !p2_block_load_mask(ldq_retry_idx)            &&
                                RegNext(dtlb.io.miss_rdy)                     &&
                                !store_needs_order                            &&
                                (w == memWidth-1).B                           && // TODO: Is this best scheduling?
                                !ldq_retry_e.bits.order_fail))

  // Can we retry a store addrgen that missed in the TLB
  // - Weird edge case when sta_retry and std_incoming for same entry in same cycle. Delay this
  val can_fire_sta_retry     = widthMap(w =>
                               ( stq_retry_e.valid                            &&
                                 stq_retry_e.bits.addr.valid                  &&
                                 stq_retry_e.bits.addr_is_virtual             &&
                                 (w == memWidth-1).B                          &&
                                 RegNext(dtlb.io.miss_rdy)                    &&
                                 !(widthMap(i => (i != w).B               &&
                                                 can_fire_std_incoming(i) &&
                                                 stq_incoming_idx(i) === stq_retry_idx).reduce(_||_))
                               ))
  // Can we commit a store
  val can_fire_store_commit  = widthMap(w =>
                               ( stq_commit_e.valid                           &&
                                !stq_commit_e.bits.uop.is_fence               &&
                                !mem_xcpt_valid                               &&
                                !stq_commit_e.bits.uop.exception              &&
                                (w == 0).B                                    &&
                                (stq_commit_e.bits.committed || ( stq_commit_e.bits.uop.is_amo      &&
                                                                  stq_commit_e.bits.addr.valid      &&
                                                                 !stq_commit_e.bits.addr_is_virtual &&
                                                                  stq_commit_e.bits.data.valid))))

  // Can we wakeup a load that was nack'd
  val can_fire_load_wakeup = widthMap(w =>
                             ( ldq_wakeup_e.valid                      &&
                               ldq_wakeup_e.bits.addr.valid            &&
                              !ldq_wakeup_e.bits.succeeded             &&
                              !ldq_wakeup_e.bits.addr_is_virtual       &&
                              !ldq_wakeup_e.bits.executed              &&
                              !ldq_wakeup_e.bits.order_fail            &&
                              !p1_block_load_mask(ldq_wakeup_idx)      &&
                              !p2_block_load_mask(ldq_wakeup_idx)      &&
                              !store_needs_order                       &&
                              (w == memWidth-1).B                      &&
                              (!ldq_wakeup_e.bits.addr_is_uncacheable || (io.core.commit_load_at_rob_head &&
                                                                          ldq_head === ldq_wakeup_idx &&
                                                                          ldq_wakeup_e.bits.st_dep_mask.asUInt === 0.U))))

  // Can we fire an incoming hellacache request
  val can_fire_hella_incoming  = WireInit(widthMap(w => false.B)) // This is assigned to in the hellashim ocntroller

  // Can we fire a hellacache request that the dcache nack'd
  val can_fire_hella_wakeup    = WireInit(widthMap(w => false.B)) // This is assigned to in the hellashim controller

  //---------------------------------------------------------
  // Controller logic. Arbitrate which request actually fires

  val exe_tlb_valid = Wire(Vec(memWidth, Bool()))
  for (w <- 0 until memWidth) {
    var tlb_avail  = true.B
    var dc_avail   = true.B
    var lcam_avail = true.B
    var rob_avail  = true.B

    def lsu_sched(can_fire: Bool, uses_tlb:Boolean, uses_dc:Boolean, uses_lcam: Boolean, uses_rob:Boolean): Bool = {
      val will_fire = can_fire && !(uses_tlb.B && !tlb_avail) &&
                                  !(uses_lcam.B && !lcam_avail) &&
                                  !(uses_dc.B && !dc_avail) &&
                                  !(uses_rob.B && !rob_avail)
      tlb_avail  = tlb_avail  && !(will_fire && uses_tlb.B)
      lcam_avail = lcam_avail && !(will_fire && uses_lcam.B)
      dc_avail   = dc_avail   && !(will_fire && uses_dc.B)
      rob_avail  = rob_avail  && !(will_fire && uses_rob.B)
      dontTouch(will_fire) // dontTouch these so we can inspect the will_fire signals
      will_fire
    }

    // The order of these statements is the priority
    // Some restrictions
    //  - Incoming ops must get precedence, can't backpresure memaddrgen
    //  - Incoming hellacache ops must get precedence over retrying ops (PTW must get precedence over retrying translation)
    will_fire_load_incoming (w) := lsu_sched(can_fire_load_incoming (w) , true , true , true , false) // TLB , DC , LCAM
    will_fire_stad_incoming (w) := lsu_sched(can_fire_stad_incoming (w) , true , false, true , true)  // TLB ,    , LCAM , ROB
    will_fire_sta_incoming  (w) := lsu_sched(can_fire_sta_incoming  (w) , true , false, true , true)  // TLB ,    , LCAM , ROB
    will_fire_std_incoming  (w) := lsu_sched(can_fire_std_incoming  (w) , false, false, false, true)  //                 , ROB
    will_fire_sfence        (w) := lsu_sched(can_fire_sfence        (w) , true , false, false, true)  // TLB ,    ,      , ROB
    will_fire_release       (w) := lsu_sched(can_fire_release       (w) , false, false, true , false) //            LCAM
    will_fire_hella_incoming(w) := lsu_sched(can_fire_hella_incoming(w) , true , true , false, false) // TLB , DC
    will_fire_hella_wakeup  (w) := lsu_sched(can_fire_hella_wakeup  (w) , false, true , false, false) //     , DC
    will_fire_load_retry    (w) := lsu_sched(can_fire_load_retry    (w) , true , true , true , false) // TLB , DC , LCAM
    will_fire_sta_retry     (w) := lsu_sched(can_fire_sta_retry     (w) , true , false, true , true)  // TLB ,    , LCAM , ROB // TODO: This should be higher priority
    will_fire_store_commit  (w) := lsu_sched(can_fire_store_commit  (w) , false, true , false, false) //     , DC
    will_fire_load_wakeup   (w) := lsu_sched(can_fire_load_wakeup   (w) , false, true , true , false) //     , DC , LCAM
    will_fire_brq_load      (w) := lsu_sched(can_fire_brq_load      (w) , false, true , false, false) //yh+
    will_fire_brq_store     (w) := lsu_sched(can_fire_brq_store     (w) , false, true , false, false) //yh+

    //yh-assert(!(exe_req(w).valid && !(will_fire_load_incoming(w) || will_fire_stad_incoming(w) || will_fire_sta_incoming(w) || will_fire_std_incoming(w) || will_fire_sfence(w))))
    assert(!(exe_req(w).valid && !(will_fire_load_incoming(w) || will_fire_stad_incoming(w) || will_fire_sta_incoming(w) ||
              will_fire_std_incoming(w) || will_fire_sfence(w) ||
              will_fire_brq_load(w) || will_fire_brq_store(w)))) //yh+

    when (will_fire_load_wakeup(w)) {
      block_load_mask(ldq_wakeup_idx)           := true.B
    } .elsewhen (will_fire_load_incoming(w)) {
      block_load_mask(exe_req(w).bits.uop.ldq_idx) := true.B
    } .elsewhen (will_fire_load_retry(w)) {
      block_load_mask(ldq_retry_idx)            := true.B
    }
    exe_tlb_valid(w) := !tlb_avail
  }
  assert((memWidth == 1).B ||
    (!(will_fire_sfence.reduce(_||_) && !will_fire_sfence.reduce(_&&_)) &&
     !will_fire_hella_incoming.reduce(_&&_) &&
     !will_fire_hella_wakeup.reduce(_&&_)   &&
     !will_fire_load_retry.reduce(_&&_)     &&
     !will_fire_sta_retry.reduce(_&&_)      &&
     !will_fire_store_commit.reduce(_&&_)   &&
     !will_fire_brq_load.reduce(_&&_)       && //yh+
     !will_fire_brq_store.reduce(_&&_)      && //yh+
     !will_fire_load_wakeup.reduce(_&&_)),
    "Some operations is proceeding down multiple pipes")

  require(memWidth <= 2)

  //--------------------------------------------
  // TLB Access

  //yh+begin
  val fuzz_exe_uop = widthMap(w =>
                              Mux(will_fire_brq_load (w) ||
                                  will_fire_brq_store (w) ,
                                  brq_exec_e.bits.uop, NullMicroOp))

  val fuzz_exe_paddr = widthMap(w =>
                                Mux(will_fire_brq_load (w) ||
                                    will_fire_brq_store (w) ,
                                    brq_exec_e.bits.addr.bits, 0.U))
  //yh+end

  assert(!(hella_state =/= h_ready && hella_req.cmd === rocket.M_SFENCE),
    "SFENCE through hella interface not supported")

  val exe_tlb_uop = widthMap(w =>
                    Mux(will_fire_load_incoming (w) ||
                        will_fire_stad_incoming (w) ||
                        will_fire_sta_incoming  (w) ||
                        will_fire_sfence        (w)  , exe_req(w).bits.uop,
                    Mux(will_fire_load_retry    (w)  , ldq_retry_e.bits.uop,
                    Mux(will_fire_sta_retry     (w)  , stq_retry_e.bits.uop,
                    Mux(will_fire_hella_incoming(w)  , NullMicroOp,
                                                       NullMicroOp)))))

  val exe_tlb_vaddr = widthMap(w =>
                    Mux(will_fire_load_incoming (w) ||
                        will_fire_stad_incoming (w) ||
                        will_fire_sta_incoming  (w)  , exe_req(w).bits.addr,
                    Mux(will_fire_sfence        (w)  , exe_req(w).bits.sfence.bits.addr,
                    Mux(will_fire_load_retry    (w)  , ldq_retry_e.bits.addr.bits,
                    Mux(will_fire_sta_retry     (w)  , stq_retry_e.bits.addr.bits,
                    Mux(will_fire_hella_incoming(w)  , hella_req.addr,
                                                       0.U))))))

  val exe_sfence = WireInit((0.U).asTypeOf(Valid(new rocket.SFenceReq)))
  for (w <- 0 until memWidth) {
    when (will_fire_sfence(w)) {
      exe_sfence := exe_req(w).bits.sfence
    }
  }

  val exe_size   = widthMap(w =>
                   Mux(will_fire_load_incoming (w) ||
                       will_fire_stad_incoming (w) ||
                       will_fire_sta_incoming  (w) ||
                       will_fire_sfence        (w) ||
                       will_fire_load_retry    (w) ||
                       will_fire_sta_retry     (w)  , exe_tlb_uop(w).mem_size,
                   Mux(will_fire_hella_incoming(w)  , hella_req.size,
                                                      0.U)))
  val exe_cmd    = widthMap(w =>
                   Mux(will_fire_load_incoming (w) ||
                       will_fire_stad_incoming (w) ||
                       will_fire_sta_incoming  (w) ||
                       will_fire_sfence        (w) ||
                       will_fire_load_retry    (w) ||
                       will_fire_sta_retry     (w)  , exe_tlb_uop(w).mem_cmd,
                   Mux(will_fire_hella_incoming(w)  , hella_req.cmd,
                                                      0.U)))

  val exe_passthr= widthMap(w =>
                   Mux(will_fire_hella_incoming(w)  , hella_req.phys,
                                                      false.B))
  val exe_kill   = widthMap(w =>
                   Mux(will_fire_hella_incoming(w)  , io.hellacache.s1_kill,
                                                      false.B))
  for (w <- 0 until memWidth) {
    dtlb.io.req(w).valid            := exe_tlb_valid(w)
    dtlb.io.req(w).bits.vaddr       := exe_tlb_vaddr(w)
    dtlb.io.req(w).bits.size        := exe_size(w)
    dtlb.io.req(w).bits.cmd         := exe_cmd(w)
    dtlb.io.req(w).bits.passthrough := exe_passthr(w)
  }
  dtlb.io.kill                      := exe_kill.reduce(_||_)
  dtlb.io.sfence                    := exe_sfence

  // exceptions
  val ma_ld = widthMap(w => will_fire_load_incoming(w) && exe_req(w).bits.mxcpt.valid) // We get ma_ld in memaddrcalc
  val ma_st = widthMap(w => (will_fire_sta_incoming(w) || will_fire_stad_incoming(w)) && exe_req(w).bits.mxcpt.valid) // We get ma_ld in memaddrcalc
  val pf_ld = widthMap(w => dtlb.io.req(w).valid && dtlb.io.resp(w).pf.ld && exe_tlb_uop(w).uses_ldq)
  val pf_st = widthMap(w => dtlb.io.req(w).valid && dtlb.io.resp(w).pf.st && exe_tlb_uop(w).uses_stq)
  val ae_ld = widthMap(w => dtlb.io.req(w).valid && dtlb.io.resp(w).ae.ld && exe_tlb_uop(w).uses_ldq)
  val ae_st = widthMap(w => dtlb.io.req(w).valid && dtlb.io.resp(w).ae.st && exe_tlb_uop(w).uses_stq)

  // TODO check for xcpt_if and verify that never happens on non-speculative instructions.
  val mem_xcpt_valids = RegNext(widthMap(w =>
                     (pf_ld(w) || pf_st(w) || ae_ld(w) || ae_st(w) || ma_ld(w) || ma_st(w)) &&
                     !io.core.exception &&
                     !IsKilledByBranch(io.core.brinfo, exe_tlb_uop(w))))
  val mem_xcpt_uops   = RegNext(widthMap(w => UpdateBrMask(io.core.brinfo, exe_tlb_uop(w))))
  val mem_xcpt_causes = RegNext(widthMap(w =>
    Mux(ma_ld(w), rocket.Causes.misaligned_load.U,
    Mux(ma_st(w), rocket.Causes.misaligned_store.U,
    Mux(pf_ld(w), rocket.Causes.load_page_fault.U,
    Mux(pf_st(w), rocket.Causes.store_page_fault.U,
    Mux(ae_ld(w), rocket.Causes.load_access.U,
                  rocket.Causes.store_access.U)))))))
  val mem_xcpt_vaddrs = RegNext(exe_tlb_vaddr)

  for (w <- 0 until memWidth) {
    assert (!(dtlb.io.req(w).valid && exe_tlb_uop(w).is_fence), "Fence is pretending to talk to the TLB")
    assert (!((will_fire_load_incoming(w) || will_fire_sta_incoming(w) || will_fire_stad_incoming(w)) &&
      exe_req(w).bits.mxcpt.valid && dtlb.io.req(w).valid &&
    !(exe_tlb_uop(w).ctrl.is_load || exe_tlb_uop(w).ctrl.is_sta)),
      "A uop that's not a load or store-address is throwing a memory exception.")
  }

  mem_xcpt_valid := mem_xcpt_valids.reduce(_||_)
  mem_xcpt_cause := mem_xcpt_causes(0)
  mem_xcpt_uop   := mem_xcpt_uops(0)
  mem_xcpt_vaddr := mem_xcpt_vaddrs(0)
  var xcpt_found = mem_xcpt_valids(0)
  var oldest_xcpt_rob_idx = mem_xcpt_uops(0).rob_idx
  for (w <- 1 until memWidth) {
    val is_older = WireInit(false.B)
    when (mem_xcpt_valids(w) &&
      (IsOlder(mem_xcpt_uops(w).rob_idx, oldest_xcpt_rob_idx, io.core.rob_head_idx) || !xcpt_found)) {
      is_older := true.B
      mem_xcpt_cause := mem_xcpt_causes(w)
      mem_xcpt_uop   := mem_xcpt_uops(w)
      mem_xcpt_vaddr := mem_xcpt_vaddrs(w)
    }
    xcpt_found = xcpt_found || mem_xcpt_valids(w)
    oldest_xcpt_rob_idx = Mux(is_older, mem_xcpt_uops(w).rob_idx, oldest_xcpt_rob_idx)
  }

  val exe_tlb_miss  = widthMap(w => dtlb.io.req(w).valid && (dtlb.io.resp(w).miss || !dtlb.io.req(w).ready))
  val exe_tlb_paddr = widthMap(w => Cat(dtlb.io.resp(w).paddr(paddrBits-1,corePgIdxBits),
                                        exe_tlb_vaddr(w)(corePgIdxBits-1,0)))
  val exe_tlb_uncacheable = widthMap(w => !(dtlb.io.resp(w).cacheable))

  for (w <- 0 until memWidth) {
    assert (exe_tlb_paddr(w) === dtlb.io.resp(w).paddr || exe_req(w).bits.sfence.valid, "[lsu] paddrs should match.")

    when (mem_xcpt_valids(w))
    {
      assert(RegNext(will_fire_load_incoming(w) || will_fire_stad_incoming(w) || will_fire_sta_incoming(w) ||
        will_fire_load_retry(w) || will_fire_sta_retry(w)))
      // Technically only faulting AMOs need this
      assert(mem_xcpt_uops(w).uses_ldq ^ mem_xcpt_uops(w).uses_stq)
      when (mem_xcpt_uops(w).uses_ldq)
      {
        ldq(mem_xcpt_uops(w).ldq_idx).bits.uop.exception := true.B
      }
        .otherwise
      {
        stq(mem_xcpt_uops(w).stq_idx).bits.uop.exception := true.B
      }
    }
  }



  //------------------------------
  // Issue Someting to Memory
  //
  // A memory op can come from many different places
  // The address either was freshly translated, or we are
  // reading a physical address from the LDQ,STQ, or the HellaCache adapter


  // defaults
  io.dmem.brinfo         := io.core.brinfo
  io.dmem.exception      := io.core.exception
  io.dmem.rob_head_idx   := io.core.rob_head_idx
  io.dmem.rob_pnr_idx    := io.core.rob_pnr_idx

  val dmem_req = Wire(Vec(memWidth, Valid(new BoomDCacheReq)))
  io.dmem.req.valid := dmem_req.map(_.valid).reduce(_||_)
  io.dmem.req.bits  := dmem_req
  val dmem_req_fire = widthMap(w => dmem_req(w).valid && io.dmem.req.fire())

  for (w <- 0 until memWidth) {
    dmem_req(w).valid := false.B
    dmem_req(w).bits.uop   := NullMicroOp
    dmem_req(w).bits.addr  := 0.U
    dmem_req(w).bits.data  := 0.U
    dmem_req(w).bits.is_hella := false.B

    io.dmem.s1_kill(w) := false.B

    when (will_fire_load_incoming(w)) {
      printf("YH+ [%d] LDQ Load incoming request, ldq_idx: %d\n",
              io.core.tsc_reg, exe_tlb_uop(w).ldq_idx)

      dmem_req(w).valid      := !exe_tlb_miss(w) && !exe_tlb_uncacheable(w)
      dmem_req(w).bits.addr  := exe_tlb_paddr(w)
      dmem_req(w).bits.uop   := exe_tlb_uop(w)

      ldq(ldq_incoming_idx(w)).bits.executed := dmem_req_fire(w)
      assert(!ldq_incoming_e(w).bits.executed)
    } .elsewhen (will_fire_load_retry(w)) {
      printf("YH+ [%d] LDQ Load retry request, ldq_idx: %d\n",
              io.core.tsc_reg, exe_tlb_uop(w).ldq_idx)

      dmem_req(w).valid      := !exe_tlb_miss(w) && !exe_tlb_uncacheable(w)
      dmem_req(w).bits.addr  := exe_tlb_paddr(w)
      dmem_req(w).bits.uop   := exe_tlb_uop(w)

      ldq(ldq_retry_idx).bits.executed := dmem_req_fire(w)
      assert(!ldq_retry_e.bits.executed)
    } .elsewhen (will_fire_store_commit(w)) {
      printf("YH+ [%d] STQ Store request, stq_idx: %d\n",
              io.core.tsc_reg, stq_commit_e.bits.uop.stq_idx)

      dmem_req(w).valid         := true.B
      dmem_req(w).bits.addr     := stq_commit_e.bits.addr.bits
      dmem_req(w).bits.data     := (new freechips.rocketchip.rocket.StoreGen(
                                    stq_commit_e.bits.uop.mem_size, 0.U,
                                    stq_commit_e.bits.data.bits,
                                    coreDataBytes)).data
      dmem_req(w).bits.uop      := stq_commit_e.bits.uop

      stq_execute_head                     := Mux(dmem_req_fire(w),
                                                WrapInc(stq_execute_head, numStqEntries),
                                                stq_execute_head)

      stq(stq_execute_head).bits.succeeded := false.B
    } .elsewhen (will_fire_load_wakeup(w)) {
      printf("YH+ [%d] LDQ Load wakeup request, ldq_idx: %d\n",
              io.core.tsc_reg, ldq_wakeup_e.bits.uop.ldq_idx)

      dmem_req(w).valid      := true.B
      dmem_req(w).bits.addr  := ldq_wakeup_e.bits.addr.bits
      dmem_req(w).bits.uop   := ldq_wakeup_e.bits.uop

      ldq(ldq_wakeup_idx).bits.executed := dmem_req_fire(w)

      assert(!ldq_wakeup_e.bits.executed && !ldq_wakeup_e.bits.addr_is_virtual)
    } .elsewhen (will_fire_hella_incoming(w)) {
      assert(hella_state === h_s1)

      dmem_req(w).valid               := !io.hellacache.s1_kill && (!exe_tlb_miss(w) || hella_req.phys)
      dmem_req(w).bits.addr           := exe_tlb_paddr(w)
      dmem_req(w).bits.data           := (new freechips.rocketchip.rocket.StoreGen(
        hella_req.size, 0.U,
        io.hellacache.s1_data.data,
        coreDataBytes)).data
      dmem_req(w).bits.uop.mem_cmd    := hella_req.cmd
      dmem_req(w).bits.uop.mem_size   := hella_req.size
      dmem_req(w).bits.uop.mem_signed := hella_req.signed
      dmem_req(w).bits.is_hella       := true.B

      hella_paddr := exe_tlb_paddr(w)
    }
      .elsewhen (will_fire_hella_wakeup(w))
    {
      assert(hella_state === h_replay)
      dmem_req(w).valid               := true.B
      dmem_req(w).bits.addr           := hella_paddr
      dmem_req(w).bits.data           := (new freechips.rocketchip.rocket.StoreGen(
        hella_req.size, 0.U,
        hella_data.data,
        coreDataBytes)).data
      dmem_req(w).bits.uop.mem_cmd    := hella_req.cmd
      dmem_req(w).bits.uop.mem_size   := hella_req.size
      dmem_req(w).bits.uop.mem_signed := hella_req.signed
      dmem_req(w).bits.is_hella       := true.B
    //yh+begin
    } .elsewhen (will_fire_brq_load(w)) {
      when (dmem_req_fire(w)) {
        printf("YH+ [%d] BRQ S_LOAD, brq_idx: %d brq_head: %d brq_tail: %d addr: %x\n",
                io.core.tsc_reg, fuzz_exe_uop(w).brq_idx, brq_head, brq_tail,
                fuzz_exe_paddr(w))
      }

      assert(fuzz_exe_uop(w).uopc === uopLD)
      //assert(fuzz_exe_uop(w).is_fuzz_ld)
      //assert(!fuzz_exe_uop(w).is_fuzz_st)

      dmem_req(w).valid      := true.B
      dmem_req(w).bits.addr  := fuzz_exe_paddr(w)
      dmem_req(w).bits.uop   := fuzz_exe_uop(w)



      assert(!brq(brq_head).bits.executed)
      brq(brq_head).bits.executed := dmem_req_fire(w)
    } .elsewhen (will_fire_brq_store(w)) {
      when (dmem_req_fire(w)) {
        printf("YH+ [%d] BRQ S_STORE, brq_idx: %d brq_head: %d brq_tail: %d addr: %x data: %d\n",
                io.core.tsc_reg, fuzz_exe_uop(w).brq_idx, brq_head, brq_tail,
                fuzz_exe_paddr(w), brq_exec_e.bits.data.bits + match_mask.reduce(_ + _))
      }

      assert(fuzz_exe_uop(w).uopc === uopSTA)
      //assert(!fuzz_exe_uop(w).is_fuzz_ld)
      //assert(fuzz_exe_uop(w).is_fuzz_st)

      dmem_req(w).valid      := true.B
      dmem_req(w).bits.addr  := fuzz_exe_paddr(w)
      dmem_req(w).bits.data  := (new freechips.rocketchip.rocket.StoreGen(
                                    brq_exec_e.bits.uop.mem_size, 0.U,
                                    brq_exec_e.bits.data.bits + match_mask.reduce(_ + _),
                                    coreDataBytes)).data
      dmem_req(w).bits.uop   := fuzz_exe_uop(w)
      brq(brq_head).bits.executed := dmem_req_fire(w)
    //yh+end
    }


    //-------------------------------------------------------------
    // Write Addr into the LAQ/SAQ
    when (will_fire_load_incoming(w) || will_fire_load_retry(w))
    {
      val ldq_idx = Mux(will_fire_load_incoming(w), ldq_incoming_idx(w), ldq_retry_idx)
      ldq(ldq_idx).bits.addr.valid          := true.B
      ldq(ldq_idx).bits.addr.bits           := Mux(exe_tlb_miss(w), exe_tlb_vaddr(w), exe_tlb_paddr(w))
      ldq(ldq_idx).bits.uop.pdst            := exe_tlb_uop(w).pdst
      ldq(ldq_idx).bits.addr_is_virtual     := exe_tlb_miss(w)
      ldq(ldq_idx).bits.addr_is_uncacheable := exe_tlb_uncacheable(w) && !exe_tlb_miss(w)

      assert(!(will_fire_load_incoming(w) && ldq_incoming_e(w).bits.addr.valid),
        "[lsu] Incoming load is overwriting a valid address")
    }

    when (will_fire_sta_incoming(w) || will_fire_stad_incoming(w) || will_fire_sta_retry(w))
    {
      val stq_idx = Mux(will_fire_sta_incoming(w) || will_fire_stad_incoming(w),
        stq_incoming_idx(w), stq_retry_idx)

      stq(stq_idx).bits.addr.valid := !pf_st(w) // Prevent AMOs from executing!
      stq(stq_idx).bits.addr.bits  := Mux(exe_tlb_miss(w), exe_tlb_vaddr(w), exe_tlb_paddr(w))
      stq(stq_idx).bits.uop.pdst   := exe_tlb_uop(w).pdst // Needed for AMOs
      stq(stq_idx).bits.addr_is_virtual := exe_tlb_miss(w)

      assert(!(will_fire_sta_incoming(w) && stq_incoming_e(w).bits.addr.valid),
        "[lsu] Incoming store is overwriting a valid address")

    }

    //-------------------------------------------------------------
    // Write data into the STQ
    if (w == 0)
      io.core.fp_stdata.ready := !will_fire_std_incoming(w) && !will_fire_stad_incoming(w)
    val fp_stdata_fire = io.core.fp_stdata.fire() && (w == 0).B
    when (will_fire_std_incoming(w) || will_fire_stad_incoming(w) || fp_stdata_fire)
    {
      val sidx = Mux(will_fire_std_incoming(w) || will_fire_stad_incoming(w),
        stq_incoming_idx(w),
        io.core.fp_stdata.bits.uop.stq_idx)
      stq(sidx).bits.data.valid := true.B
      stq(sidx).bits.data.bits  := Mux(will_fire_std_incoming(w) || will_fire_stad_incoming(w),
        exe_req(w).bits.data,
        io.core.fp_stdata.bits.data)

      assert(!(stq(sidx).bits.data.valid),
        "[lsu] Incoming store is overwriting a valid data entry")
    }
  }
  val will_fire_stdf_incoming = io.core.fp_stdata.fire()
  require (xLen >= fLen) // for correct SDQ size

  //-------------------------------------------------------------
  //-------------------------------------------------------------
  // Cache Access Cycle (Mem)
  //-------------------------------------------------------------
  //-------------------------------------------------------------
  // Note the DCache may not have accepted our request

  val exe_req_killed = widthMap(w => IsKilledByBranch(io.core.brinfo, exe_req(w).bits.uop))
  val stdf_killed = IsKilledByBranch(io.core.brinfo, io.core.fp_stdata.bits.uop)

  val fired_load_incoming  = widthMap(w => RegNext(will_fire_load_incoming(w) && !exe_req_killed(w)))
  val fired_stad_incoming  = widthMap(w => RegNext(will_fire_stad_incoming(w) && !exe_req_killed(w)))
  val fired_sta_incoming   = widthMap(w => RegNext(will_fire_sta_incoming (w) && !exe_req_killed(w)))
  val fired_std_incoming   = widthMap(w => RegNext(will_fire_std_incoming (w) && !exe_req_killed(w)))
  val fired_stdf_incoming  = RegNext(will_fire_stdf_incoming && !stdf_killed)
  val fired_sfence         = RegNext(will_fire_sfence)
  val fired_release        = RegNext(will_fire_release)
  val fired_load_retry     = widthMap(w => RegNext(will_fire_load_retry   (w) && !IsKilledByBranch(io.core.brinfo, ldq_retry_e.bits.uop)))
  val fired_sta_retry      = widthMap(w => RegNext(will_fire_sta_retry    (w) && !IsKilledByBranch(io.core.brinfo, stq_retry_e.bits.uop)))
  val fired_store_commit   = RegNext(will_fire_store_commit)
  val fired_load_wakeup    = widthMap(w => RegNext(will_fire_load_wakeup  (w) && !IsKilledByBranch(io.core.brinfo, ldq_wakeup_e.bits.uop)))
  val fired_hella_incoming = RegNext(will_fire_hella_incoming)
  val fired_hella_wakeup   = RegNext(will_fire_hella_wakeup)

  val mem_incoming_uop     = RegNext(widthMap(w => UpdateBrMask(io.core.brinfo, exe_req(w).bits.uop)))
  val mem_ldq_incoming_e   = RegNext(widthMap(w => UpdateBrMask(io.core.brinfo, ldq_incoming_e(w))))
  val mem_stq_incoming_e   = RegNext(widthMap(w => UpdateBrMask(io.core.brinfo, stq_incoming_e(w))))
  val mem_ldq_wakeup_e     = RegNext(UpdateBrMask(io.core.brinfo, ldq_wakeup_e))
  val mem_ldq_retry_e      = RegNext(UpdateBrMask(io.core.brinfo, ldq_retry_e))
  val mem_stq_retry_e      = RegNext(UpdateBrMask(io.core.brinfo, stq_retry_e))
  val mem_ldq_e            = widthMap(w =>
                             Mux(fired_load_incoming(w), mem_ldq_incoming_e(w),
                             Mux(fired_load_retry   (w), mem_ldq_retry_e,
                             Mux(fired_load_wakeup  (w), mem_ldq_wakeup_e, (0.U).asTypeOf(Valid(new LDQEntry))))))
  val mem_stq_e            = widthMap(w =>
                             Mux(fired_stad_incoming(w) ||
                                 fired_sta_incoming (w), mem_stq_incoming_e(w),
                             Mux(fired_sta_retry    (w), mem_stq_retry_e, (0.U).asTypeOf(Valid(new STQEntry)))))
  val mem_stdf_uop         = RegNext(UpdateBrMask(io.core.brinfo, io.core.fp_stdata.bits.uop))


  val mem_tlb_miss             = RegNext(exe_tlb_miss)
  val mem_tlb_uncacheable      = RegNext(exe_tlb_uncacheable)
  //yh-val mem_paddr                = RegNext(widthMap(w => dmem_req(w).bits.addr))
  //yh+begin
  val fired_brq_load  = RegNext(will_fire_brq_load)
  val fired_brq_store = RegNext(will_fire_brq_store)

  val mem_paddr       = RegNext(widthMap(w => 
                                Mux(fired_brq_load(w) ||
                                fired_brq_store(w), 0.U,
                                dmem_req(w).bits.addr)))
  //yh+end


  // Task 1: Clr ROB busy bit
  val clr_bsy_valid   = RegInit(widthMap(w => false.B))
  val clr_bsy_rob_idx = Reg(Vec(memWidth, UInt(robAddrSz.W)))
  val clr_bsy_brmask  = Reg(Vec(memWidth, UInt(maxBrCount.W)))

  for (w <- 0 until memWidth) {
    clr_bsy_valid   (w) := false.B
    clr_bsy_rob_idx (w) := 0.U
    clr_bsy_brmask  (w) := 0.U


    when (fired_stad_incoming(w)) {
      clr_bsy_valid   (w) := mem_stq_incoming_e(w).valid           &&
                            !mem_tlb_miss(w)                       &&
                            !mem_stq_incoming_e(w).bits.uop.is_amo &&
                            !IsKilledByBranch(io.core.brinfo, mem_stq_incoming_e(w).bits.uop)
      clr_bsy_rob_idx (w) := mem_stq_incoming_e(w).bits.uop.rob_idx
      clr_bsy_brmask  (w) := GetNewBrMask(io.core.brinfo, mem_stq_incoming_e(w).bits.uop)
    } .elsewhen (fired_sta_incoming(w)) {
      clr_bsy_valid   (w) := mem_stq_incoming_e(w).valid            &&
                             mem_stq_incoming_e(w).bits.data.valid  &&
                            !mem_tlb_miss(w)                        &&
                            !mem_stq_incoming_e(w).bits.uop.is_amo  &&
                            !IsKilledByBranch(io.core.brinfo, mem_stq_incoming_e(w).bits.uop)
      clr_bsy_rob_idx (w) := mem_stq_incoming_e(w).bits.uop.rob_idx
      clr_bsy_brmask  (w) := GetNewBrMask(io.core.brinfo, mem_stq_incoming_e(w).bits.uop)
    } .elsewhen (fired_std_incoming(w)) {
      clr_bsy_valid   (w) := mem_stq_incoming_e(w).valid                 &&
                             mem_stq_incoming_e(w).bits.addr.valid       &&
                            !mem_stq_incoming_e(w).bits.addr_is_virtual  &&
                            !mem_stq_incoming_e(w).bits.uop.is_amo       &&
                            !IsKilledByBranch(io.core.brinfo, mem_stq_incoming_e(w).bits.uop)
      clr_bsy_rob_idx (w) := mem_stq_incoming_e(w).bits.uop.rob_idx
      clr_bsy_brmask  (w) := GetNewBrMask(io.core.brinfo, mem_stq_incoming_e(w).bits.uop)
    } .elsewhen (fired_sfence(w)) {
      clr_bsy_valid   (w) := (w == 0).B // SFence proceeds down all paths, only allow one to clr the rob
      clr_bsy_rob_idx (w) := mem_incoming_uop(w).rob_idx
      clr_bsy_brmask  (w) := GetNewBrMask(io.core.brinfo, mem_incoming_uop(w))
    } .elsewhen (fired_sta_retry(w)) {
      clr_bsy_valid   (w) := mem_stq_retry_e.valid            &&
                             mem_stq_retry_e.bits.data.valid  &&
                            !mem_tlb_miss(w)                  &&
                            !mem_stq_retry_e.bits.uop.is_amo  &&
                            !IsKilledByBranch(io.core.brinfo, mem_stq_retry_e.bits.uop)
      clr_bsy_rob_idx (w) := mem_stq_retry_e.bits.uop.rob_idx
      clr_bsy_brmask  (w) := GetNewBrMask(io.core.brinfo, mem_stq_retry_e.bits.uop)
    }

    io.core.clr_bsy(w).valid := clr_bsy_valid(w) &&
                               !IsKilledByBranch(io.core.brinfo, clr_bsy_brmask(w)) &&
                               !io.core.exception && !RegNext(io.core.exception) && !RegNext(RegNext(io.core.exception))
    io.core.clr_bsy(w).bits  := clr_bsy_rob_idx(w)
  }

  val stdf_clr_bsy_valid   = RegInit(false.B)
  val stdf_clr_bsy_rob_idx = Reg(UInt(robAddrSz.W))
  val stdf_clr_bsy_brmask  = Reg(UInt(maxBrCount.W))
  stdf_clr_bsy_valid   := false.B
  stdf_clr_bsy_rob_idx := 0.U
  stdf_clr_bsy_brmask  := 0.U
  when (fired_stdf_incoming) {
    val s_idx = mem_stdf_uop.stq_idx
    stdf_clr_bsy_valid   := stq(s_idx).valid                 &&
                            stq(s_idx).bits.addr.valid       &&
                            !stq(s_idx).bits.addr_is_virtual &&
                            !stq(s_idx).bits.uop.is_amo      &&
                            !IsKilledByBranch(io.core.brinfo, mem_stdf_uop)
    stdf_clr_bsy_rob_idx := mem_stdf_uop.rob_idx
    stdf_clr_bsy_brmask  := GetNewBrMask(io.core.brinfo, mem_stdf_uop)
  }



  io.core.clr_bsy(memWidth).valid := stdf_clr_bsy_valid &&
                                    !IsKilledByBranch(io.core.brinfo, stdf_clr_bsy_brmask) &&
                                    !io.core.exception && !RegNext(io.core.exception) && !RegNext(RegNext(io.core.exception))
  io.core.clr_bsy(memWidth).bits  := stdf_clr_bsy_rob_idx



  // Task 2: Do LD-LD. ST-LD searches for ordering failures
  //         Do LD-ST search for forwarding opportunities
  // We have the opportunity to kill a request we sent last cycle. Use it wisely!

  // We translated a store last cycle
  val do_st_search = widthMap(w => (fired_stad_incoming(w) || fired_sta_incoming(w) || fired_sta_retry(w)) && !mem_tlb_miss(w))
  // We translated a load last cycle
  val do_ld_search = widthMap(w => ((fired_load_incoming(w) || fired_load_retry(w)) && !mem_tlb_miss(w)) ||
                     fired_load_wakeup(w))
  // We are making a local line visible to other harts
  val do_release_search = widthMap(w => fired_release(w))

  // Store addrs don't go to memory yet, get it from the TLB response
  // Load wakeups don't go through TLB, get it through memory
  // Load incoming and load retries go through both

  val lcam_addr  = widthMap(w => Mux(fired_stad_incoming(w) || fired_sta_incoming(w) || fired_sta_retry(w),
                                     RegNext(exe_tlb_paddr(w)), mem_paddr(w)))
  val lcam_uop   = widthMap(w => Mux(do_st_search(w), mem_stq_e(w).bits.uop,
                                 Mux(do_ld_search(w), mem_ldq_e(w).bits.uop, NullMicroOp)))

  val lcam_mask  = widthMap(w => GenByteMask(lcam_addr(w), lcam_uop(w).mem_size))
  val lcam_st_dep_mask = widthMap(w => mem_ldq_e(w).bits.st_dep_mask)
  val lcam_is_release = widthMap(w => fired_release(w))
  val lcam_ldq_idx  = widthMap(w =>
                      Mux(fired_load_incoming(w), mem_incoming_uop(w).ldq_idx,
                      Mux(fired_load_wakeup  (w), RegNext(ldq_wakeup_idx),
                      Mux(fired_load_retry   (w), RegNext(ldq_retry_idx), 0.U))))
  val lcam_stq_idx  = widthMap(w =>
                      Mux(fired_stad_incoming(w) ||
                          fired_sta_incoming (w), mem_incoming_uop(w).stq_idx,
                      Mux(fired_sta_retry    (w), RegNext(stq_retry_idx), 0.U)))

  val can_forward = WireInit(widthMap(w =>
    Mux(fired_load_incoming(w) || fired_load_retry(w), !mem_tlb_uncacheable(w),
      !ldq(lcam_ldq_idx(w)).bits.addr_is_uncacheable)))

  // Mask of stores which we conflict on address with
  val ldst_addr_matches    = WireInit(widthMap(w => VecInit((0 until numStqEntries).map(x=>false.B))))
  // Mask of stores which we can forward from
  val ldst_forward_matches = WireInit(widthMap(w => VecInit((0 until numStqEntries).map(x=>false.B))))

  val executing_loads  = WireInit(VecInit((0 until numLdqEntries).map(x=>false.B))) // Loads which are firing (searching the LDQ/STQ) in this stage
  val failed_loads     = WireInit(VecInit((0 until numLdqEntries).map(x=>false.B))) // Loads which we will report as failures (throws a mini-exception)
  val succeeding_loads = WireInit(VecInit((0 until numLdqEntries).map(x=>false.B))) // Loads which are responding to core in the next stage
  val nacking_loads    = WireInit(VecInit((0 until numLdqEntries).map(x=>false.B))) // Loads which are being nacked by dcache in the next stage

  for (w <- 0 until memWidth) {
    when (do_ld_search(w)) {
      executing_loads(lcam_ldq_idx(w)) := true.B
    }
  }

  for (i <- 0 until numLdqEntries) {
    val l_valid = ldq(i).valid
    val l_bits  = ldq(i).bits
    val l_addr  = ldq(i).bits.addr.bits
    val l_mask  = GenByteMask(l_addr, l_bits.uop.mem_size)


    val block_addr_matches = widthMap(w => lcam_addr(w) >> blockOffBits === l_addr >> blockOffBits)
    val dword_addr_matches = widthMap(w => block_addr_matches(w) && lcam_addr(w)(blockOffBits-1,3) === l_addr(blockOffBits-1,3))
    val mask_match   = widthMap(w => (l_mask & lcam_mask(w)) === l_mask)
    val mask_overlap = widthMap(w => (l_mask & lcam_mask(w)).orR)
    val l_is_succeeding = succeeding_loads(i)

    // Searcher is a store
    for (w <- 0 until memWidth) {
      when (do_release_search(w) &&
            l_valid              &&
            l_bits.addr.valid    &&
            block_addr_matches(w)) {
        // This load has been observed, so if a younger load to the same address has not
        // executed yet, this load must be squashed
        ldq(i).bits.observed := true.B
      } .elsewhen (do_st_search(w)                                                                                             &&
                   l_valid                                                                                                     &&
                   l_bits.addr.valid                                                                                           &&
                   ((l_bits.executed && !l_bits.execute_ignore && !executing_loads(i)) || l_bits.succeeded || l_is_succeeding) &&
                   !l_bits.addr_is_virtual                                                                                     &&
                   l_bits.st_dep_mask(lcam_stq_idx(w))                                                                         &&
                   dword_addr_matches(w)                                                                                       &&
                   mask_overlap(w)) {
        val forwarded_is_older = IsOlder(l_bits.forward_stq_idx, lcam_stq_idx(w), l_bits.youngest_stq_idx)
        // We are older than this load, which overlapped us.
        when (!l_bits.forward_std_val || // If the load wasn't forwarded, it definitely failed
          ((l_bits.forward_stq_idx =/= lcam_stq_idx(w)) && forwarded_is_older)) { // If the load forwarded from us, we might be ok
          when (l_bits.succeeded || l_is_succeeding) { // If the younger load already succeeded, we are screwed. Throw order fail
            ldq(i).bits.order_fail := true.B
            failed_loads(i)        := true.B
          } .otherwise { // If the younger load hasn't responded yet, tell it to kill its response
            ldq(i).bits.execute_ignore := true.B
          }
        }
      } .elsewhen (do_ld_search(w)            &&
                   l_valid                    &&
                   l_bits.addr.valid          &&
                   !l_bits.addr_is_virtual    &&
                   dword_addr_matches(w)      &&
                   mask_overlap(w)) {
        val searcher_is_older = IsOlder(lcam_ldq_idx(w), i.U, ldq_head)
        when (searcher_is_older) {
          when (l_bits.executed        &&
                !l_bits.execute_ignore &&
                !executing_loads(i)    && // If the load is proceeding in parallel we don't need to kill it
                l_bits.observed) {        // Its only a ordering failure if the cache line was observed between the younger load and us
            when (l_bits.succeeded || l_is_succeeding) { // If the younger load is executing and succeeded, we are screwed. Throw order fail
              ldq(i).bits.order_fail := true.B
              failed_loads(i)        := true.B
            } .otherwise { // The younger load hasn't returned yet, we can kill its response
              ldq(i).bits.execute_ignore := true.B
            }
          }
        } .elsewhen (lcam_ldq_idx(w) =/= i.U) {
          // The load is older, and either it hasn't executed, it was nacked, or it is ignoring its response
          // we need to kill ourselves, and prevent forwarding
          val older_nacked = nacking_loads(i)
          when (!l_bits.executed || older_nacked || l_bits.execute_ignore) {
            io.dmem.s1_kill(w)                 := RegNext(dmem_req_fire(w))
            ldq(lcam_ldq_idx(w)).bits.executed := false.B
            can_forward(w)                     := false.B
          }
        }
      }
    }
  }
  for (i <- 0 until numStqEntries) {
    val s_addr = stq(i).bits.addr.bits
    val s_uop  = stq(i).bits.uop
    val dword_addr_matches = widthMap(w =>
                             ( stq(i).bits.addr.valid      &&
                              !stq(i).bits.addr_is_virtual &&
                              (s_addr(corePAddrBits-1,3) === lcam_addr(w)(corePAddrBits-1,3))))
    val write_mask = GenByteMask(s_addr, s_uop.mem_size)
    for (w <- 0 until memWidth) {
      when (do_ld_search(w) && stq(i).valid && lcam_st_dep_mask(w)(i)) {
        when (((lcam_mask(w) & write_mask) === lcam_mask(w)) && !s_uop.is_fence && dword_addr_matches(w) && can_forward(w))
        {
          ldst_addr_matches(w)(i)            := true.B
          ldst_forward_matches(w)(i)         := true.B
          io.dmem.s1_kill(w)                 := RegNext(dmem_req_fire(w))
          ldq(lcam_ldq_idx(w)).bits.executed := false.B
        }
          .elsewhen (((lcam_mask(w) & write_mask) =/= 0.U) && dword_addr_matches(w))
        {
          ldst_addr_matches(w)(i)            := true.B
          io.dmem.s1_kill(w)                 := RegNext(dmem_req_fire(w))
          ldq(lcam_ldq_idx(w)).bits.executed := false.B
        }
          .elsewhen (s_uop.is_fence || s_uop.is_amo)
        {
          ldst_addr_matches(w)(i)            := true.B
          io.dmem.s1_kill(w)                 := RegNext(dmem_req_fire(w))
          ldq(lcam_ldq_idx(w)).bits.executed := false.B
        }
      }
    }
  }

  // Find the youngest store which the load is dependent on
  val forwarding_age_logic = Seq.fill(memWidth) { Module(new ForwardingAgeLogic(numStqEntries)) }
  for (w <- 0 until memWidth) {
    forwarding_age_logic(w).io.addr_matches    := ldst_addr_matches(w).asUInt
    forwarding_age_logic(w).io.youngest_st_idx := lcam_uop(w).stq_idx
  }
  val forwarding_idx = widthMap(w => forwarding_age_logic(w).io.forwarding_idx)

  // Forward if st-ld forwarding is possible from the writemask and loadmask
  val mem_forward_valid       = widthMap(w =>
                                (ldst_forward_matches(w)(forwarding_idx(w))        &&
                                 !IsKilledByBranch(io.core.brinfo, lcam_uop(w))    &&
                                 !io.core.exception && !RegNext(io.core.exception)))
  val mem_forward_ldq_idx     = lcam_ldq_idx
  val mem_forward_ld_addr     = lcam_addr
  val mem_forward_stq_idx     = forwarding_idx

  // Task 3: Clr unsafe bit in ROB for succesful translations
  //         Delay this a cycle to avoid going ahead of the exception broadcast
  //         The unsafe bit is cleared on the first translation, so no need to fire for load wakeups
  for (w <- 0 until memWidth) {
    io.core.clr_unsafe(w).valid := RegNext((do_st_search(w) || do_ld_search(w)) && !fired_load_wakeup(w))
    io.core.clr_unsafe(w).bits  := RegNext(lcam_uop(w).rob_idx)
  }

  // detect which loads get marked as failures, but broadcast to the ROB the oldest failing load
  // TODO encapsulate this in an age-based  priority-encoder
  //   val l_idx = AgePriorityEncoder((Vec(Vec.tabulate(numLdqEntries)(i => failed_loads(i) && i.U >= laq_head)
  //   ++ failed_loads)).asUInt)
  val temp_bits = (VecInit(VecInit.tabulate(numLdqEntries)(i =>
    failed_loads(i) && i.U >= ldq_head) ++ failed_loads)).asUInt
  val l_idx = PriorityEncoder(temp_bits)

  // one exception port, but multiple causes!
  // - 1) the incoming store-address finds a faulting load (it is by definition younger)
  // - 2) the incoming load or store address is excepting. It must be older and thus takes precedent.
  val r_xcpt_valid = RegInit(false.B)
  val r_xcpt       = Reg(new Exception)

  val ld_xcpt_valid = failed_loads.reduce(_|_)
  val ld_xcpt_uop   = ldq(Mux(l_idx >= numLdqEntries.U, l_idx - numLdqEntries.U, l_idx)).bits.uop

  val use_mem_xcpt = (mem_xcpt_valid && IsOlder(mem_xcpt_uop.rob_idx, ld_xcpt_uop.rob_idx, io.core.rob_head_idx)) || !ld_xcpt_valid

  val xcpt_uop = Mux(use_mem_xcpt, mem_xcpt_uop, ld_xcpt_uop)

  r_xcpt_valid := (ld_xcpt_valid || mem_xcpt_valid) &&
                   !io.core.exception &&
                   !IsKilledByBranch(io.core.brinfo, xcpt_uop)
  r_xcpt.uop         := xcpt_uop
  r_xcpt.uop.br_mask := GetNewBrMask(io.core.brinfo, xcpt_uop)
  r_xcpt.cause       := Mux(use_mem_xcpt, mem_xcpt_cause, MINI_EXCEPTION_MEM_ORDERING)
  r_xcpt.badvaddr    := mem_xcpt_vaddr // TODO is there another register we can use instead?

  io.core.lxcpt.valid := r_xcpt_valid && !io.core.exception && !IsKilledByBranch(io.core.brinfo, r_xcpt.uop)
  io.core.lxcpt.bits  := r_xcpt

  // Task 4: Speculatively wakeup loads 1 cycle before they come back
  io.core.spec_ld_wakeup.valid := enableFastLoadUse.B          &&
                                  fired_load_incoming(0)       &&
                                  !mem_incoming_uop(0).fp_val  &&
                                  mem_incoming_uop(0).pdst =/= 0.U
  io.core.spec_ld_wakeup.bits  := mem_incoming_uop(0).pdst
  // TODO: Do this on retry? Wakeup?



  //-------------------------------------------------------------
  //-------------------------------------------------------------
  // Writeback Cycle (St->Ld Forwarding Path)
  //-------------------------------------------------------------
  //-------------------------------------------------------------

  val wb_forward_valid    = RegNext(mem_forward_valid)
  val wb_forward_ldq_idx  = RegNext(mem_forward_ldq_idx)
  val wb_forward_ld_addr  = RegNext(mem_forward_ld_addr)
  val wb_forward_stq_idx  = RegNext(mem_forward_stq_idx)

  // Handle Memory Responses and nacks
  //----------------------------------
  for (w <- 0 until memWidth) {
    io.core.exe(w).iresp.valid := false.B
    io.core.exe(w).fresp.valid := false.B
  }

  val dmem_resp_fired = WireInit(widthMap(w => false.B))

  for (w <- 0 until memWidth) {
    // Handle nacks
    when (io.dmem.nack(w).valid)
    {
      // We have to re-execute this!
      when (io.dmem.nack(w).bits.is_hella)
      {
        assert(hella_state === h_wait || hella_state === h_dead)
      }
      //yh+begin
        .elsewhen (io.dmem.nack(w).bits.uop.is_br_or_jmp)
      {
        when (io.dmem.nack(w).bits.uop.uses_ldq &&
                brq(io.dmem.nack(w).bits.uop.brq_idx).bits.state === s_load) {
          printf("YH+ [%d] BRQ Load nack arrived, brq_idx: %d\n",
                  io.core.tsc_reg, io.dmem.nack(w).bits.uop.brq_idx)
          assert(brq(io.dmem.nack(w).bits.uop.brq_idx).bits.state === s_load)

          brq(io.dmem.nack(w).bits.uop.brq_idx).bits.executed := false.B
        } .elsewhen (io.dmem.nack(w).bits.uop.uses_stq &&
                brq(io.dmem.nack(w).bits.uop.brq_idx).bits.state === s_store) {
          printf("YH+ [%d] BRQ Store nack arrived, brq_idx: %d\n",
                  io.core.tsc_reg, io.dmem.nack(w).bits.uop.brq_idx)
          assert(brq(io.dmem.nack(w).bits.uop.brq_idx).bits.state === s_store)

          brq(io.dmem.nack(w).bits.uop.brq_idx).bits.executed := false.B
        } .otherwise {
          printf("YH+ [%d] Assert nack arrived, brq_idx: %d\n",
                  io.core.tsc_reg, io.dmem.nack(w).bits.uop.brq_idx)
        }

      }
      //yh+end
        .elsewhen (io.dmem.nack(w).bits.uop.uses_ldq)
      {
        printf("YH+ [%d] LDQ Load nack arrived, ldq_idx: %d\n",
                io.core.tsc_reg, io.dmem.nack(w).bits.uop.ldq_idx)

        assert(ldq(io.dmem.nack(w).bits.uop.ldq_idx).bits.executed)
        ldq(io.dmem.nack(w).bits.uop.ldq_idx).bits.executed  := false.B
        ldq(io.dmem.nack(w).bits.uop.ldq_idx).bits.execute_ignore  := false.B
        nacking_loads(io.dmem.nack(w).bits.uop.ldq_idx) := true.B
      }
        .otherwise
      {
        printf("YH+ [%d] STQ Store nack arrived, stq_idx: %d\n",
                io.core.tsc_reg, io.dmem.nack(w).bits.uop.stq_idx)

        assert(io.dmem.nack(w).bits.uop.uses_stq)
        when (IsOlder(io.dmem.nack(w).bits.uop.stq_idx, stq_execute_head, stq_head)) {
          stq_execute_head := io.dmem.nack(w).bits.uop.stq_idx
        }
      }
    }
    // Handle the response
    when (io.dmem.resp(w).valid)
    {
      //yh+begin
      when (io.dmem.resp(w).bits.uop.is_br_or_jmp)
      {
        when (io.dmem.resp(w).bits.uop.uses_ldq)
        {
          assert(!io.dmem.resp(w).bits.is_hella)
          //assert(!(io.dmem.resp(w).bits.uop.uses_stq || io.dmem.resp(w).bits.uop.uses_ldq))

          printf("YH+ [%d] BRQ Load resp arrived! brq_idx: %d data: %d\n",
                  io.core.tsc_reg, io.dmem.resp(w).bits.uop.brq_idx,
                  io.dmem.resp(w).bits.data)

          brq(io.dmem.resp(w).bits.uop.brq_idx).bits.uop.uopc := uopSTA
          brq(io.dmem.resp(w).bits.uop.brq_idx).bits.uop.mem_cmd := rocket.M_XWR
          brq(io.dmem.resp(w).bits.uop.brq_idx).bits.uop.uses_ldq := false.B
          brq(io.dmem.resp(w).bits.uop.brq_idx).bits.uop.uses_stq := true.B
          brq(io.dmem.resp(w).bits.uop.brq_idx).bits.executed := false.B
          brq(io.dmem.resp(w).bits.uop.brq_idx).bits.state := s_store
          //brq(io.dmem.resp(w).bits.uop.brq_idx).bits.state := s_done //yh+
          brq(io.dmem.resp(w).bits.uop.brq_idx).bits.data.valid := true.B
          brq(io.dmem.resp(w).bits.uop.brq_idx).bits.data.bits := io.dmem.resp(w).bits.data
        }
          .elsewhen (io.dmem.resp(w).bits.uop.uses_stq)
        {
          assert(!io.dmem.resp(w).bits.is_hella)
          //assert(!(io.dmem.resp(w).bits.uop.uses_stq || io.dmem.resp(w).bits.uop.uses_ldq))

          printf("YH+ [%d] BRQ Store resp arrived! brq_idx: %d data: %d\n",
                  io.core.tsc_reg, io.dmem.resp(w).bits.uop.brq_idx,
                  io.dmem.resp(w).bits.data)

          brq(io.dmem.resp(w).bits.uop.brq_idx).bits.state := s_done
        } .otherwise {
          printf("YH+ [%d] BRQ Assert resp arrived, brq_idx: %d\n",
                  io.core.tsc_reg, io.dmem.resp(w).bits.uop.brq_idx)

        }
      }
      //yh-when (io.dmem.resp(w).bits.uop.uses_ldq)
        .elsewhen (io.dmem.resp(w).bits.uop.uses_ldq)
      {
        printf("YH+ [%d] LDQ Load resp arrived! ldq_idx: %d data: %d\n",
                io.core.tsc_reg, io.dmem.resp(w).bits.uop.ldq_idx,
                io.dmem.resp(w).bits.data)

        assert(!io.dmem.resp(w).bits.is_hella)
        val ldq_idx = io.dmem.resp(w).bits.uop.ldq_idx
        val send_iresp = ldq(ldq_idx).bits.uop.dst_rtype === RT_FIX
        val send_fresp = ldq(ldq_idx).bits.uop.dst_rtype === RT_FLT

        io.core.exe(w).iresp.bits.uop  := ldq(ldq_idx).bits.uop
        io.core.exe(w).fresp.bits.uop  := ldq(ldq_idx).bits.uop
        io.core.exe(w).iresp.valid     := send_iresp && !ldq(ldq_idx).bits.execute_ignore
        io.core.exe(w).iresp.bits.data := io.dmem.resp(w).bits.data
        io.core.exe(w).fresp.valid     := send_fresp && !ldq(ldq_idx).bits.execute_ignore
        io.core.exe(w).fresp.bits.data := io.dmem.resp(w).bits.data

        assert(send_iresp ^ send_fresp)
        dmem_resp_fired(w) := true.B

        ldq(ldq_idx).bits.succeeded      := io.core.exe(w).iresp.valid || io.core.exe(w).fresp.valid
        ldq(ldq_idx).bits.execute_ignore := false.B
        when (ldq(ldq_idx).bits.execute_ignore) {
          // We were told to ignore this response because of order fail
          // Clear the execute bit, so we can re-fire this load
          ldq(ldq_idx).bits.executed := false.B
        }

        ldq(ldq_idx).bits.debug_wb_data  := io.dmem.resp(w).bits.data
      }
        .elsewhen (io.dmem.resp(w).bits.uop.uses_stq)
      {
        printf("YH+ [%d] STQ Store resp arrived! stq_idx: %d data: %d\n",
                io.core.tsc_reg, io.dmem.resp(w).bits.uop.stq_idx,
                io.dmem.resp(w).bits.data)

        assert(!io.dmem.resp(w).bits.is_hella)
        stq(io.dmem.resp(w).bits.uop.stq_idx).bits.succeeded := true.B
        when (io.dmem.resp(w).bits.uop.is_amo) {
          dmem_resp_fired(w) := true.B
          io.core.exe(w).iresp.valid     := true.B
          io.core.exe(w).iresp.bits.uop  := stq(io.dmem.resp(w).bits.uop.stq_idx).bits.uop
          io.core.exe(w).iresp.bits.data := io.dmem.resp(w).bits.data

          stq(io.dmem.resp(w).bits.uop.stq_idx).bits.debug_wb_data := io.dmem.resp(w).bits.data
        }
      }
    }


    when (dmem_resp_fired(w) && wb_forward_valid(w))
    {
      // Twiddle thumbs. Can't forward because dcache response takes precedence
    }
      .elsewhen (!dmem_resp_fired(w) && wb_forward_valid(w))
    {
      val f_idx       = wb_forward_ldq_idx(w)
      val forward_uop = ldq(f_idx).bits.uop
      val stq_e       = stq(wb_forward_stq_idx(w))
      val data_ready  = stq_e.bits.data.valid
      val live        = !IsKilledByBranch(io.core.brinfo, forward_uop)
      val storegen = new freechips.rocketchip.rocket.StoreGen(
                                stq_e.bits.uop.mem_size, stq_e.bits.addr.bits,
                                stq_e.bits.data.bits, coreDataBytes)
      val loadgen  = new freechips.rocketchip.rocket.LoadGen(
                                forward_uop.mem_size, forward_uop.mem_signed,
                                wb_forward_ld_addr(w),
                                storegen.data, false.B, coreDataBytes)

      io.core.exe(w).iresp.valid := (forward_uop.dst_rtype === RT_FIX) && data_ready && live
      io.core.exe(w).fresp.valid := (forward_uop.dst_rtype === RT_FLT) && data_ready && live
      io.core.exe(w).iresp.bits.uop  := forward_uop
      io.core.exe(w).fresp.bits.uop  := forward_uop
      io.core.exe(w).iresp.bits.data := loadgen.data
      io.core.exe(w).fresp.bits.data := loadgen.data

      when (data_ready && live) {
        ldq(f_idx).bits.succeeded := data_ready
        ldq(f_idx).bits.forward_std_val := true.B
        ldq(f_idx).bits.forward_stq_idx := wb_forward_stq_idx(w)

        ldq(f_idx).bits.debug_wb_data   := loadgen.data
      }
      assert(!ldq(f_idx).bits.execute_ignore)
    }
    //when (io.core.exe(w).iresp.valid && io.core.exe(w).iresp.bits.uop.uses_ldq) {
    when (io.core.exe(w).iresp.valid && (io.core.exe(w).iresp.bits.uop.uses_ldq && !io.core.exe(w).iresp.bits.uop.is_br_or_jmp)) { //yh+
      succeeding_loads(io.core.exe(w).iresp.bits.uop.ldq_idx) := true.B
    //} .elsewhen (io.core.exe(w).fresp.valid && io.core.exe(w).fresp.bits.uop.uses_ldq) {
    } .elsewhen (io.core.exe(w).fresp.valid && (io.core.exe(w).fresp.bits.uop.uses_ldq && !io.core.exe(w).fresp.bits.uop.is_br_or_jmp)) { //yh+
      succeeding_loads(io.core.exe(w).fresp.bits.uop.ldq_idx) := true.B
    }
  }

  // Initially assume the speculative load wakeup failed
  io.core.ld_miss         := RegNext(io.core.spec_ld_wakeup.valid)
  when (io.core.exe(0).iresp.valid && io.core.exe(0).iresp.bits.uop.ldq_idx === RegNext(mem_incoming_uop(0).ldq_idx)) {
    // We correcty speculated last cycle, so we don't send miss signal
    io.core.ld_miss := false.B
  }

  //-------------------------------------------------------------
  // Kill speculated entries on branch mispredict
  //-------------------------------------------------------------
  //-------------------------------------------------------------

  // Kill stores
  val st_brkilled_mask = Wire(Vec(numStqEntries, Bool()))
  for (i <- 0 until numStqEntries)
  {
    st_brkilled_mask(i) := false.B

    when (stq(i).valid)
    {
      stq(i).bits.uop.br_mask := GetNewBrMask(io.core.brinfo, stq(i).bits.uop.br_mask)

      when (IsKilledByBranch(io.core.brinfo, stq(i).bits.uop))
      {
        stq(i).valid           := false.B
        stq(i).bits.addr.valid := false.B
        stq(i).bits.data.valid := false.B
        st_brkilled_mask(i)    := true.B
      }
    }

    assert (!(IsKilledByBranch(io.core.brinfo, stq(i).bits.uop) && stq(i).valid && stq(i).bits.committed),
      "Branch is trying to clear a committed store.")
  }

  // Kill loads
  for (i <- 0 until numLdqEntries)
  {
    when (ldq(i).valid)
    {
      ldq(i).bits.uop.br_mask := GetNewBrMask(io.core.brinfo, ldq(i).bits.uop.br_mask)
      when (IsKilledByBranch(io.core.brinfo, ldq(i).bits.uop))
      {
        ldq(i).valid           := false.B
        ldq(i).bits.addr.valid := false.B
      }
    }
  }

  //-------------------------------------------------------------
  when (io.core.brinfo.valid && io.core.brinfo.mispredict && !io.core.exception)
  {
    stq_tail := io.core.brinfo.stq_idx
    ldq_tail := io.core.brinfo.ldq_idx
  }

  //-------------------------------------------------------------
  //-------------------------------------------------------------
  // dequeue old entries on commit
  //-------------------------------------------------------------
  //-------------------------------------------------------------

  var temp_stq_commit_head = stq_commit_head
  var temp_ldq_head        = ldq_head
  for (w <- 0 until coreWidth)
  {
    val commit_store = io.core.commit.valids(w) && io.core.commit.uops(w).uses_stq
    val commit_load  = io.core.commit.valids(w) && io.core.commit.uops(w).uses_ldq
    val idx = Mux(commit_store, temp_stq_commit_head, temp_ldq_head)

    when (commit_store)
    {
      stq(idx).bits.committed := true.B
    } .elsewhen (commit_load) {
      assert (ldq(idx).valid, "[lsu] trying to commit an un-allocated load entry.")
      assert ((ldq(idx).bits.executed || ldq(idx).bits.forward_std_val) && ldq(idx).bits.succeeded ,
        "[lsu] trying to commit an un-executed load entry.")

      ldq(idx).valid                 := false.B
      ldq(idx).bits.addr.valid       := false.B
      ldq(idx).bits.executed         := false.B
      ldq(idx).bits.succeeded        := false.B
      ldq(idx).bits.order_fail       := false.B
      ldq(idx).bits.forward_std_val  := false.B

    }

    if (MEMTRACE_PRINTF) {
      when (commit_store || commit_load) {
        val uop    = Mux(commit_store, stq(idx).bits.uop, ldq(idx).bits.uop)
        val addr   = Mux(commit_store, stq(idx).bits.addr.bits, ldq(idx).bits.addr.bits)
        val stdata = Mux(commit_store, stq(idx).bits.data.bits, 0.U)
        val wbdata = Mux(commit_store, stq(idx).bits.debug_wb_data, ldq(idx).bits.debug_wb_data)
        printf("MT %x %x %x %x %x %x %x\n",
          io.core.tsc_reg, uop.uopc, uop.mem_cmd, uop.mem_size, addr, stdata, wbdata)
      }
    }

    temp_stq_commit_head = Mux(commit_store,
                               WrapInc(temp_stq_commit_head, numStqEntries),
                               temp_stq_commit_head)

    temp_ldq_head        = Mux(commit_load,
                               WrapInc(temp_ldq_head, numLdqEntries),
                               temp_ldq_head)
  }
  stq_commit_head := temp_stq_commit_head
  ldq_head        := temp_ldq_head

  // store has been committed AND successfully sent data to memory
  when (stq(stq_head).valid && stq(stq_head).bits.committed)
  {
    when (stq(stq_head).bits.uop.is_fence && !io.dmem.ordered) {
      io.dmem.force_order := true.B
      store_needs_order   := true.B
    }
    clear_store := Mux(stq(stq_head).bits.uop.is_fence, io.dmem.ordered,
                                                        stq(stq_head).bits.succeeded)
  }

  when (clear_store)
  {
    stq(stq_head).valid           := false.B
    stq(stq_head).bits.addr.valid := false.B
    stq(stq_head).bits.data.valid := false.B
    stq(stq_head).bits.succeeded  := false.B
    stq(stq_head).bits.committed  := false.B

    stq_head := WrapInc(stq_head, numStqEntries)
    when (stq(stq_head).bits.uop.is_fence)
    {
      stq_execute_head := WrapInc(stq_execute_head, numStqEntries)
    }
  }


  // -----------------------
  // Hellacache interface
  // We need to time things like a HellaCache would
  io.hellacache.req.ready := false.B
  io.hellacache.s2_nack   := false.B
  io.hellacache.s2_xcpt   := (0.U).asTypeOf(new rocket.HellaCacheExceptions)
  io.hellacache.resp.valid := false.B
  when (hella_state === h_ready) {
    io.hellacache.req.ready := true.B
    when (io.hellacache.req.fire()) {
      hella_req   := io.hellacache.req.bits
      hella_state := h_s1
    }
  } .elsewhen (hella_state === h_s1) {
    can_fire_hella_incoming(memWidth-1) := true.B

    hella_data := io.hellacache.s1_data
    hella_xcpt := dtlb.io.resp(memWidth-1)

    when (io.hellacache.s1_kill) {
      when (will_fire_hella_incoming(memWidth-1) && dmem_req_fire(memWidth-1)) {
        hella_state := h_dead
      } .otherwise {
        hella_state := h_ready
      }
    } .elsewhen (will_fire_hella_incoming(memWidth-1) && dmem_req_fire(memWidth-1)) {
      hella_state := h_s2
    } .otherwise {
      hella_state := h_s2_nack
    }
  } .elsewhen (hella_state === h_s2_nack) {
    io.hellacache.s2_nack := true.B
    hella_state := h_ready
  } .elsewhen (hella_state === h_s2) {
    io.hellacache.s2_xcpt := hella_xcpt
    when (io.hellacache.s2_kill || hella_xcpt.asUInt =/= 0.U) {
      hella_state := h_dead
    } .otherwise {
      hella_state := h_wait
    }
  } .elsewhen (hella_state === h_wait) {
    for (w <- 0 until memWidth) {
      when (io.dmem.resp(w).valid && io.dmem.resp(w).bits.is_hella) {
        hella_state := h_ready

        io.hellacache.resp.valid       := true.B
        io.hellacache.resp.bits.addr   := hella_req.addr
        io.hellacache.resp.bits.tag    := hella_req.tag
        io.hellacache.resp.bits.cmd    := hella_req.cmd
        io.hellacache.resp.bits.signed := hella_req.signed
        io.hellacache.resp.bits.size   := hella_req.size
        io.hellacache.resp.bits.data   := io.dmem.resp(w).bits.data
      } .elsewhen (io.dmem.nack(w).valid && io.dmem.nack(w).bits.is_hella) {
        hella_state := h_replay
      }
    }
  } .elsewhen (hella_state === h_replay) {
    can_fire_hella_wakeup(memWidth-1) := true.B

    when (will_fire_hella_wakeup(memWidth-1) && dmem_req_fire(memWidth-1)) {
      hella_state := h_wait
    }
  } .elsewhen (hella_state === h_dead) {
    for (w <- 0 until memWidth) {
      when (io.dmem.resp(w).valid && io.dmem.resp(w).bits.is_hella) {
        hella_state := h_ready
      }
    }
  }

  //-------------------------------------------------------------
  // Exception / Reset

  // for the live_store_mask, need to kill stores that haven't been committed
  val st_exc_killed_mask = WireInit(VecInit((0 until numStqEntries).map(x=>false.B)))

  when (reset.asBool || io.core.exception)
  {
    ldq_head := 0.U
    ldq_tail := 0.U

    when (reset.asBool)
    {
      stq_head := 0.U
      stq_tail := 0.U
      stq_commit_head  := 0.U
      stq_execute_head := 0.U

      for (i <- 0 until numStqEntries)
      {
        stq(i).valid           := false.B
        stq(i).bits.addr.valid := false.B
        stq(i).bits.data.valid := false.B
        stq(i).bits.uop        := NullMicroOp
      }
    }
      .otherwise // exception
    {
      stq_tail := stq_commit_head

      for (i <- 0 until numStqEntries)
      {
        when (!stq(i).bits.committed && !stq(i).bits.succeeded)
        {
          stq(i).valid           := false.B
          stq(i).bits.addr.valid := false.B
          stq(i).bits.data.valid := false.B
          st_exc_killed_mask(i)  := true.B
        }
      }
    }

    for (i <- 0 until numLdqEntries)
    {
      ldq(i).valid           := false.B
      ldq(i).bits.addr.valid := false.B
      ldq(i).bits.executed   := false.B
    }
  }

  //-------------------------------------------------------------
  // Live Store Mask
  // track a bit-array of stores that are alive
  // (could maybe be re-produced from the stq_head/stq_tail, but need to know include spec_killed entries)

  // TODO is this the most efficient way to compute the live store mask?
  live_store_mask := next_live_store_mask &
                    ~(st_brkilled_mask.asUInt) &
                    ~(st_exc_killed_mask.asUInt)
}

/**
 * Object to take an address and generate an 8-bit mask of which bytes within a
 * double-word.
 */
object GenByteMask
{
   def apply(addr: UInt, size: UInt): UInt =
   {
      val mask = Wire(UInt(8.W))
      mask := MuxCase(255.U(8.W), Array(
                   (size === 0.U) -> (1.U(8.W) << addr(2,0)),
                   (size === 1.U) -> (3.U(8.W) << (addr(2,1) << 1.U)),
                   (size === 2.U) -> Mux(addr(2), 240.U(8.W), 15.U(8.W)),
                   (size === 3.U) -> 255.U(8.W)))
      mask
   }
}

/**
 * ...
 */
class ForwardingAgeLogic(num_entries: Int)(implicit p: Parameters) extends BoomModule()(p)
{
   val io = IO(new Bundle
   {
      val addr_matches    = Input(UInt(num_entries.W)) // bit vector of addresses that match
                                                       // between the load and the SAQ
      val youngest_st_idx = Input(UInt(stqAddrSz.W)) // needed to get "age"

      val forwarding_val  = Output(Bool())
      val forwarding_idx  = Output(UInt(stqAddrSz.W))
   })

   // generating mask that zeroes out anything younger than tail
   val age_mask = Wire(Vec(num_entries, Bool()))
   for (i <- 0 until num_entries)
   {
      age_mask(i) := true.B
      when (i.U >= io.youngest_st_idx) // currently the tail points PAST last store, so use >=
      {
         age_mask(i) := false.B
      }
   }

   // Priority encoder with moving tail: double length
   val matches = Wire(UInt((2*num_entries).W))
   matches := Cat(io.addr_matches & age_mask.asUInt,
                  io.addr_matches)

   val found_match = Wire(Bool())
   found_match       := false.B
   io.forwarding_idx := 0.U

   // look for youngest, approach from the oldest side, let the last one found stick
   for (i <- 0 until (2*num_entries))
   {
      when (matches(i))
      {
         found_match := true.B
         io.forwarding_idx := (i % num_entries).U
      }
   }

   io.forwarding_val := found_match
}
