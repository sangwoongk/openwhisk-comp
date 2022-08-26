#!/bin/bash

ansible-playbook -i environments/local invoker.yml -e mode=clean
ansible-playbook -i environments/local controller.yml -e mode=clean
ansible-playbook -i environments/local controller.yml -e skip_pull_runtimes=true
ansible-playbook -i environments/local invoker.yml -e skip_pull_runtimes=true
