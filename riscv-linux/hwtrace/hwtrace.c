#include <linux/kernel.h>
#include <linux/syscalls.h>
#include <linux/sched.h>
#include <linux/slab.h>
#include <asm/csr.h>
#include <asm/io.h>

#include "hwtrace.h"


// Global varibles shared with ht-kmod
unsigned int tmap = 0;
unsigned int umap = 0;
unsigned int tlbq = 0;
unsigned int ulbq = 0;
EXPORT_SYMBOL(tmap);
EXPORT_SYMBOL(umap);
EXPORT_SYMBOL(tlbq);
EXPORT_SYMBOL(ulbq);

void *HT_CMAP[HT_CNT] = {0};
void *HT_LBQ[HT_CNT] = {0};
EXPORT_SYMBOL(HT_CMAP);
EXPORT_SYMBOL(HT_LBQ);

/* Enable tracing */
static inline __attribute__((always_inline)) void __ht_enable(void) {
  csr_write(CSR_CMAP_ENABLE, 1);
  csr_write(CSR_LBQ_ENABLE, 1);
}

/* Set cmap-related CSRs */
static inline __attribute__((always_inline)) void __set_ht_regs(struct task_struct *t) {
  csr_write(CSR_CMAP, virt_to_phys(t->cmap));
  csr_write(CSR_MSZ, t->msz);
  csr_write(CSR_HT_SADDR, t->ht_saddr);
  csr_write(CSR_HT_EADDR, t->ht_eaddr);
}

SYSCALL_DEFINE3(hwtrace_set, unsigned int, ht_id, void *, saddr, void *, eaddr) {
	printk("[SYS_SET] RISCV set registers\n");

  /* reset memory */
	memset(HT_CMAP[ht_id], 0, HT_MSZ);
	memset(HT_LBQ[ht_id], 0, HT_QSZ);
	
  /* set task struct with info from global HT array */
	current->cmap = HT_CMAP[ht_id];
	current->msz = HT_MSZ;
	current->mloc = 0;
	current->ht_saddr = saddr;
  current->ht_eaddr = eaddr;
  current->lbq = HT_LBQ[ht_id];
  /* NOTE: only supporting lbq of 33 entries */
	current->qsz = 33;

  /* initialize and start tracing */
  __set_ht_regs(current);
  __ht_enable();

	return 0;
}
