#ifndef _LINUX_HWTRACE_H
#define _LINUX_HWTRACE_H

// maximum number of HT instances, i.e., cmap/lbq
#define HT_CNT 64

// shared memory size of cmap between kernel and userspace
#define HT_MPAGES 16
#define HT_MSZ (PAGE_SIZE * HT_MPAGES) // 4K * 16 = 65536

// shared memory size of lbq between kernel and userspace
// NOTE: this is the minimum size for shared memory
#define HT_QPAGES 1
#define HT_QSZ (PAGE_SIZE * HT_QPAGES) // 4K * 1 = 4096

#endif
