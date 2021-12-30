RISCV_TOOL_BIN=$(PWD)/../../../../riscv-gnu-toolchain/build/bin
CC=$(RISCV_TOOL_BIN)/riscv64-unknown-linux-gnu-gcc

DRIVER=lbq.ko
KERN_SRC=../../../../riscv-linux
RISCV_ROOTFS=../../../../riscv-busybear/kmod

lbq-objs := module.o
ccflags-y += -Wno-declaration-after-statement
obj-m += lbq.o

all:
	make -C ${KERN_SRC} ARCH=riscv CROSS_COMPILE=riscv64-unknown-linux-gnu- M=$(PWD) modules
	cp $(DRIVER) $(RISCV_ROOTFS)

clean:
	make -C ${KERN_SRC} M=$(PWD) clean
