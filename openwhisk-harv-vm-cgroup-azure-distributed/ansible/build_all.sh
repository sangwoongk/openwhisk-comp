#!/bin/bash

sudo ./build.sh &
sudo ./build_in_vm.sh

wait < <(jobs -p)
echo "Build all complete!"