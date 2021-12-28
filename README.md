# Hardware Support to Improve Fuzzing Performance and Precision

SNAP was developed using [FireSim](https://fires.im), an open-source hardware simulation platform that runs on cloud FPGAs (Amazon EC2 F1).
To run SNAP, you would need to replace the existing HW design directory (generator) with the SNAP’s one and build a new HW design that will be mapped to FPGA instances.
While all the necessary instructions to run FPGA instances are provided in the [FireSim’s documentation](https://docs.fires.im/en/latest/index.html), we add some detailed steps that might not be clearly indicated in the documentation.

## Initial Setup/Installation for FireSim

Please refer to https://docs.fires.im/en/latest/Initial-Setup/index.html.

## Setting up the FireSim Repo

When you set up a manager instance during
[the initial setup process](https://github.com/sslab-gatech/SNAP#initial-setupinstallation-for-firesim),
check out a specific tag, `1.9.0`.

```
git clone https://github.com/firesim/firesim.git
cd firesim
git checkout 1.9.0 # command to check out a specific tag
./build-setup.sh fast
```

## Replace the existing “generator” directory with the SNAP design.

## Building Hardware Design for SNAP

In `~/firesim/deploy/config_build.ini`, \
Add a bucket name: ```s3bucketname=firesim-snap``` \
Add to ```[builds]``` (comment out other things) \
```firesim-boom-singlecore-no-nic-l2-llc4mb-ddr3-snap``` \
Add to ```[agfistoshare]``` (comment out other things)
```firesim-boom-singlecore-no-nic-l2-llc4mb-ddr3-snap```

In ```~/firesim/deploy/config_build_recipes.ini```, \
Add \
[firesim-boom-singlecore-no-nic-l2-llc4mb-ddr3-snap] \
DESIGN=FireSim \
TARGET_CONFIG=DDR3FRFCFSLLC4MB_FireSimLargeBoomConfig \
PLATFORM_CONFIG=F75MHz_BaseF1Config \
instancetype=f1.2xlarge \
deploytriplet=None

Run ```firesim buildafi``` (This will take a long time, ~10 hours).

Refer to the [documentation](https://docs.fires.im/en/latest/Building-a-FireSim-AFI.html) for more details.

5. Running FireSim Simulations with SNAP

In `~/firesim/deploy/config_runtime.ini`, change the default HW config. \
`defaulthwconfig=firesim-boom-singlecore-no-nic-l2-llc4mb-ddr3-snap`

In `~/firesim/deploy/config_hwdb.ini`, add a new HW DB.
```
[firesim-boom-singlecore-no-nic-l2-llc4mb-ddr3-snap]
agfi=agfi-062b20613c52a2313 # replace with your agfi after HW build completes
deploytripletoverride=None
customruntimeconfig=None
```

Launch FPGA instance(s) following the [documentation](https://docs.fires.im/en/latest/Running-Simulations-Tutorial/index.html).

After an FPGA instance is launched, insert modules into the kernel.
```
# insmod cmap.ko
# insmod lbq.ko
```

Run binaries to trace!


## Publications
```
@inproceedings{ding:snap,
  title        = {{Hardware Support to Improve Fuzzing Performance and Precision (to appear)}},
  author       = {Ren Ding and Yonghae Kim and Fan Sang and Wen Xu and Gururaj Saileshwar and Taesoo Kim},
  booktitle    = {Proceedings of the 28th ACM Conference on Computer and Communications Security (CCS)},
  month        = nov,
  year         = 2021,
  address      = {Seoul, South Korea},
}
```
