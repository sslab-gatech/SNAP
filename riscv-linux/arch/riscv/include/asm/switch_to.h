/* SPDX-License-Identifier: GPL-2.0-only */
/*
 * Copyright (C) 2012 Regents of the University of California
 */

#ifndef _ASM_RISCV_SWITCH_TO_H
#define _ASM_RISCV_SWITCH_TO_H

#include <linux/sched/task_stack.h>
#include <asm/processor.h>
#include <asm/ptrace.h>
#include <asm/csr.h>
#include <asm/io.h>

#ifdef CONFIG_FPU
extern void __fstate_save(struct task_struct *save_to);
extern void __fstate_restore(struct task_struct *restore_from);

static inline void __fstate_clean(struct pt_regs *regs)
{
	regs->sstatus = (regs->sstatus & ~SR_FS) | SR_FS_CLEAN;
}

static inline void fstate_off(struct task_struct *task,
			      struct pt_regs *regs)
{
	regs->sstatus = (regs->sstatus & ~SR_FS) | SR_FS_OFF;
}

static inline void fstate_save(struct task_struct *task,
			       struct pt_regs *regs)
{
	if ((regs->sstatus & SR_FS) == SR_FS_DIRTY) {
		__fstate_save(task);
		__fstate_clean(regs);
	}
}

static inline void fstate_restore(struct task_struct *task,
				  struct pt_regs *regs)
{
	if ((regs->sstatus & SR_FS) != SR_FS_OFF) {
		__fstate_restore(task);
		__fstate_clean(regs);
	}
}

static inline void __switch_to_aux(struct task_struct *prev,
				   struct task_struct *next)
{
	struct pt_regs *regs;

	regs = task_pt_regs(prev);
	if (unlikely(regs->sstatus & SR_SD))
		fstate_save(prev, regs);
	fstate_restore(next, task_pt_regs(next));
}

extern bool has_fpu;
#else
#define has_fpu false
#define fstate_save(task, regs) do { } while (0)
#define fstate_restore(task, regs) do { } while (0)
#define __switch_to_aux(__prev, __next) do { } while (0)
#endif

/* Enable tracing */
static inline __attribute__((always_inline)) void __ht_enable(void) {
  csr_write(CSR_CMAP_ENABLE, 1);
  csr_write(CSR_LBQ_ENABLE, 1);
}

/* Disable tracing */
static inline __attribute__((always_inline)) void __ht_disable(void) {
  csr_write(CSR_CMAP_ENABLE, 0);
  csr_write(CSR_LBQ_ENABLE, 0);
}

/* Set cmap-related CSRs */
static inline __attribute__((always_inline)) void __set_ht_regs(struct task_struct *t) {
  csr_write(CSR_CMAP, virt_to_phys(t->cmap));
  csr_write(CSR_MSZ, t->msz);
  csr_write(CSR_HT_SADDR, t->ht_saddr);
  csr_write(CSR_HT_EADDR, t->ht_eaddr);
}

/* Read LBQ entries before flushing */
static inline __attribute__((always_inline)) void __flush_lbq(struct task_struct *t) {
  // read 33 entries from LBQ, with each entry size of 8 bytes
  *(uint64_t *)(t->lbq+ 8 * 0) = csr_read(CSR_LBQ + 0);
  *(uint64_t *)(t->lbq + 8 * 1) = csr_read(CSR_LBQ + 1);
  *(uint64_t *)(t->lbq + 8 * 2) = csr_read(CSR_LBQ + 2);
  *(uint64_t *)(t->lbq + 8 * 3) = csr_read(CSR_LBQ + 3);
  *(uint64_t *)(t->lbq + 8 * 4) = csr_read(CSR_LBQ + 4);
  *(uint64_t *)(t->lbq + 8 * 5) = csr_read(CSR_LBQ + 5);
  *(uint64_t *)(t->lbq + 8 * 6) = csr_read(CSR_LBQ + 6);
  *(uint64_t *)(t->lbq + 8 * 7) = csr_read(CSR_LBQ + 7);
  *(uint64_t *)(t->lbq + 8 * 8) = csr_read(CSR_LBQ + 8);
  *(uint64_t *)(t->lbq + 8 * 9) = csr_read(CSR_LBQ + 9);
  *(uint64_t *)(t->lbq + 8 * 10) = csr_read(CSR_LBQ + 10);
  *(uint64_t *)(t->lbq + 8 * 11) = csr_read(CSR_LBQ + 11);
  *(uint64_t *)(t->lbq + 8 * 12) = csr_read(CSR_LBQ + 12);
  *(uint64_t *)(t->lbq + 8 * 13) = csr_read(CSR_LBQ + 13);
  *(uint64_t *)(t->lbq + 8 * 14) = csr_read(CSR_LBQ + 14);
  *(uint64_t *)(t->lbq + 8 * 15) = csr_read(CSR_LBQ + 15);
  *(uint64_t *)(t->lbq + 8 * 16) = csr_read(CSR_LBQ + 16);
  *(uint64_t *)(t->lbq + 8 * 17) = csr_read(CSR_LBQ + 17);
  *(uint64_t *)(t->lbq + 8 * 18) = csr_read(CSR_LBQ + 18);
  *(uint64_t *)(t->lbq + 8 * 19) = csr_read(CSR_LBQ + 19);
  *(uint64_t *)(t->lbq + 8 * 20) = csr_read(CSR_LBQ + 20);
  *(uint64_t *)(t->lbq + 8 * 21) = csr_read(CSR_LBQ + 21);
  *(uint64_t *)(t->lbq + 8 * 22) = csr_read(CSR_LBQ + 22);
  *(uint64_t *)(t->lbq + 8 * 23) = csr_read(CSR_LBQ + 23);
  *(uint64_t *)(t->lbq + 8 * 24) = csr_read(CSR_LBQ + 24);
  *(uint64_t *)(t->lbq + 8 * 25) = csr_read(CSR_LBQ + 25);
  *(uint64_t *)(t->lbq + 8 * 26) = csr_read(CSR_LBQ + 26);
  *(uint64_t *)(t->lbq + 8 * 27) = csr_read(CSR_LBQ + 27);
  *(uint64_t *)(t->lbq + 8 * 28) = csr_read(CSR_LBQ + 28);
  *(uint64_t *)(t->lbq + 8 * 29) = csr_read(CSR_LBQ + 29);
  *(uint64_t *)(t->lbq + 8 * 30) = csr_read(CSR_LBQ + 30);
  *(uint64_t *)(t->lbq + 8 * 31) = csr_read(CSR_LBQ + 31);
  *(uint64_t *)(t->lbq + 8 * 32) = csr_read(CSR_LBQ + 32);

  // flush lbq
  csr_write(CSR_LBQ_FLUSH, 1);
  csr_write(CSR_LBQ_FLUSH, 0);
}

/* riscv setup tracing info in context switches */
static inline __attribute__((always_inline)) void __hwtrace_setup(struct task_struct *prev,
					struct task_struct *next)
{
  // if previous process is traced, disable tracing and read out the entries in lbq
  if (prev->cmap){
    __ht_disable();
    __flush_lbq(prev);
  }
  // if next process is traced, set cmap-related CSRs before enable tracing
  if (next->cmap) {
    __set_ht_regs(next);
    __ht_enable();
  }
}

extern struct task_struct *__switch_to(struct task_struct *,
				       struct task_struct *);

#define switch_to(prev, next, last)			\
do {							\
	struct task_struct *__prev = (prev);		\
	struct task_struct *__next = (next);		\
	if (has_fpu)					\
		__switch_to_aux(__prev, __next);	\
	__hwtrace_setup(__prev, __next);	\
	((last) = __switch_to(__prev, __next));		\
} while (0)

#endif /* _ASM_RISCV_SWITCH_TO_H */
