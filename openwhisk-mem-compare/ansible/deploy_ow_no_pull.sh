#!/bin/bash

ansible-playbook -i environments/local openwhisk.yml -e skip_pull_runtimes=true
