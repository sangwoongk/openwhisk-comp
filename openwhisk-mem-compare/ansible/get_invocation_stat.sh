#!/bin/bash
WORKER_IP=190
# vmIP=170

NODE_START=0
NODE_END=6

PORT_START=0
PORT_END=7

WARM_CNT=0
DUP_CNT=0
OTHER_CNT=0

INV_CNT=0

get_num_errors() {
	for ((nodes=$NODE_START;nodes<=$NODE_END;nodes++))
	do
		for ((ip=$PORT_START;ip<=$PORT_END;ip++))
		do
			INVOKER_ID=$(($nodes*8+$ip))
			CMD="cat /tmp/wsklogs/invoker$INVOKER_ID/invoker${INVOKER_ID}_logs.log | grep -a ERROR | wc -l"
			WARM_CMD="cat /tmp/wsklogs/invoker$INVOKER_ID/invoker${INVOKER_ID}_logs.log | grep -a ERROR | grep warm | wc -l"
			DUP_CMD="cat /tmp/wsklogs/invoker$INVOKER_ID/invoker${INVOKER_ID}_logs.log | grep -a ERROR | grep put | wc -l"

			RES=$(sudo ssh -p $((3355+$ip)) root@10.150.21.$(($WORKER_IP+$nodes)) $CMD)
			WARM_RES=$(sudo ssh -p $((3355+$ip)) root@10.150.21.$(($WORKER_IP+$nodes)) $WARM_CMD)
			DUP_RES=$(sudo ssh -p $((3355+$ip)) root@10.150.21.$(($WORKER_IP+$nodes)) $DUP_CMD)
			OTHER_RES=$(($RES-$WARM_RES-$DUP_RES))

			WARM_CNT=$(($WARM_CNT+$WARM_RES))
			DUP_CNT=$(($DUP_CNT+$DUP_RES))
			OTHER_CNT=$(($OTHER_CNT+$OTHER_RES))
			echo "[$(($WORKER_IP+$nodes)):$ip invoker$INVOKER_ID] warm error: $WARM_RES, duplicated error: $DUP_RES, other errors: $OTHER_RES"
		done
		echo ""
	done
	echo "[total] warm error: $WARM_CNT, duplicated error: $DUP_CNT, other: $OTHER_CNT"
}

get_error_single() {
	INVOKER_ID=$(($1*8+$2))
	CMD="cat /tmp/wsklogs/invoker$INVOKER_ID/invoker${INVOKER_ID}_logs.log | grep -a ERROR | wc -l"
	WARM_CMD="cat /tmp/wsklogs/invoker$INVOKER_ID/invoker${INVOKER_ID}_logs.log | grep -a ERROR | grep warm | wc -l"
	DUP_CMD="cat /tmp/wsklogs/invoker$INVOKER_ID/invoker${INVOKER_ID}_logs.log | grep -a ERROR | grep put | wc -l"

	RES=$(sudo ssh -p $((3355+$2)) root@10.150.21.$(($WORKER_IP+$1)) $CMD)
	WARM_RES=$(sudo ssh -p $((3355+$2)) root@10.150.21.$(($WORKER_IP+$1)) $WARM_CMD)
	DUP_RES=$(sudo ssh -p $((3355+$2)) root@10.150.21.$(($WORKER_IP+$1)) $DUP_CMD)
	OTHER_RES=$(($RES-$WARM_RES-$DUP_RES))

	WARM_CNT=$(($WARM_CNT+$WARM_RES))
	DUP_CNT=$(($DUP_CNT+$DUP_RES))
	OTHER_CNT=$(($OTHER_CNT+$OTHER_RES))
	echo "[$(($WORKER_IP+$1)):$2 invoker$INVOKER_ID] warm error: $WARM_RES, duplicated error: $DUP_RES, other errors: $OTHER_RES"
}

get_num_errors_background() {
	for ((nodes=$NODE_START;nodes<=$NODE_END;nodes++))
	do
		for ((ip=$PORT_START;ip<=$PORT_END;ip++))
		do
			get_error_single $nodes $ip &
		done
	done
	wait < <(jobs -p)
	echo "[total] warm error: $WARM_CNT, duplicated error: $DUP_CNT, other: $OTHER_CNT"
}

clear_log() {
	for ((nodes=$NODE_START;nodes<=$NODE_END;nodes++))
	do
		for ((ip=$PORT_START;ip<=$PORT_END;ip++))
		do
			INVOKER_ID=$((($nodes*8)+$ip))
			CMD="echo > /tmp/wsklogs/invoker$INVOKER_ID/invoker${INVOKER_ID}_logs.log"
			sudo ssh -p $((3355+$ip)) root@10.150.21.$(($WORKER_IP+$nodes)) $CMD &
		done
	done

	wait < <(jobs -p)
	echo "Log clear complete!"
}

input=$1

# empty input
if [ $input = "error" ]
then
	get_num_errors_background
elif [ $input = "clear" ]
then
	clear_log
else
	echo "No matching function"
fi