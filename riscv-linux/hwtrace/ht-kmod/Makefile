SUBDIRS := $(wildcard */.)

all: $(SUBDIRS)
$(SUBDIRS):
	cd $@ && make clean && make

.PHONY: all $(SUBDIRS)
