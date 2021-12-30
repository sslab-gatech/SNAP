#include <linux/init.h>
#include <linux/module.h>
#include <linux/kernel.h>
#include <linux/device.h>
#include <linux/slab.h>
#include <linux/fs.h>
#include <linux/kdev_t.h>
#include <linux/cdev.h>
#include <linux/mm.h>
#include <linux/string.h>

#include "../../hwtrace.h"

#define DEVICE_NAME "lbq"
#define CLASS_NAME "lbq"

// hwtrace.c
extern unsigned int tlbq;
extern unsigned int ulbq;
extern void *HT_LBQ[HT_CNT];

// Device variables
static dev_t ht_num;
static struct cdev ht_device;
static struct class *ht_class;

// Prototype functions
static int ht_open(struct inode *, struct file *);
static int ht_mmap(struct file *, struct vm_area_struct *);
static int ht_close(struct inode *, struct file *);

// Operations allowed
static struct file_operations ht_fops =
{
	.owner = THIS_MODULE,
	.open = ht_open,
	.mmap = ht_mmap,
	.release = ht_close,
};

static int __init ht_init(void) {
	int ret;
	struct device *dev_ret;

	// Register major & minor number for device
	if ((ret = alloc_chrdev_region(&ht_num, 0, 1, DEVICE_NAME)) < 0) {
		printk(KERN_ALERT "[LBQ_ERR] Failed to register device numbers!\n");
		return ret;
	}

	// Register device class
	if (IS_ERR(ht_class = class_create(THIS_MODULE, CLASS_NAME))) {
		unregister_chrdev_region(ht_num, 1);
		printk(KERN_ALERT "[LBQ_ERR] Failed to register device class!\n");
		return PTR_ERR(ht_class);
	}

	// Register device driver
	if (IS_ERR(dev_ret = device_create(ht_class, NULL, ht_num, NULL, DEVICE_NAME))) {
		class_destroy(ht_class);
		unregister_chrdev_region(ht_num, 1);
		printk(KERN_ALERT "[LBQ_ERR] Failed to create device!\n");
		return PTR_ERR(dev_ret);
	}

	cdev_init(&ht_device, &ht_fops);
	if ((ret = cdev_add(&ht_device, ht_num, 1)) < 0) {
		device_destroy(ht_class, ht_num);
		class_destroy(ht_class);
		unregister_chrdev_region(ht_num, 1);
		printk(KERN_ALERT "[LBQ_ERR] Failed to add device!\n");
		return ret;
	}

	printk(KERN_ALERT "[LBQ] Module inserted (major = %d)\n", MAJOR(ht_num));
	return 0;
}

static int ht_open(struct inode *inodep, struct file *filep) {
	unsigned int i, j;

	if (tlbq == HT_CNT) {
		printk(KERN_ALERT "[LBQ_ERR] Exceeding allowance!\n");
		return -EFAULT;
	}

	i = tlbq;
	if ((HT_LBQ[i] = kmalloc(HT_QSZ, GFP_KERNEL)) == NULL) {
		printk(KERN_ALERT "[LBQ_ERR] No memory!\n");
		return -ENOMEM;
	}
	// set reserved bit for not swapping out
	for (j = 0; j < HT_QSZ; j += PAGE_SIZE)
		SetPageReserved(virt_to_page(((unsigned long)HT_LBQ[i]) + j));

	printk(KERN_ALERT "[LBQ] Open LBQ #%u (%px)\n", i, HT_LBQ[i]);
	tlbq++;
	return 0;
}

static int ht_mmap(struct file *filep, struct vm_area_struct *vma) {
	unsigned int i;
	// PAGE_SIZE as unit
	unsigned long size = (unsigned long)(vma->vm_end - vma->vm_start);

	if (ulbq == tlbq) {
		printk(KERN_ALERT "[LBQ_ERR] Exhausted, open() before mmap()!\n");
		return -EFAULT;
	}
	if (size != HT_QSZ) {
		printk(KERN_ALERT "[LBQ_ERR] mmap() size too large!\n");
		return -EINVAL;
	}

	i = ulbq;
	// remap to userspace
	if (remap_pfn_range(vma,
				vma->vm_start,
				virt_to_pfn(HT_LBQ[i]),
				size,
				vma->vm_page_prot) < 0) {
		printk(KERN_ALERT "[LBQ_ERR] Share memory failed!\n");
		return -EIO;
	}
	ulbq++;
	return 0;
}

static int ht_close(struct inode *inodep, struct file *filep) {
	return 0;
}

static void __exit ht_cleanup(void) {
	cdev_del(&ht_device);
	device_destroy(ht_class, ht_num);
	class_unregister(ht_class);
	class_destroy(ht_class);
	unregister_chrdev_region(ht_num, 1);
	printk(KERN_ALERT "[LBQ] Module removed\n");
}

module_init(ht_init);
module_exit(ht_cleanup);

MODULE_LICENSE("GPL");
MODULE_AUTHOR("Motherfxxk");
MODULE_DESCRIPTION("LBQ module for htrace");
