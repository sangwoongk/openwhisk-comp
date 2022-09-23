#!/bin/bash
for i in {0..500};
do
	#wsk -i action update app$i function.py --docker openwhisk/action-python-v3.7 --param execTime 1000
	#wsk -i action update app$i function.py --param execTime 1000
	num=`printf "%.3d" $i`
	wsk -i action update func$num function.js
	#wsk -i action update func$num function.py --param execTime 1000
done
