#!/bin/bash

ansible-playbook -i environments/local openwhisk.yml -e mode=clean
