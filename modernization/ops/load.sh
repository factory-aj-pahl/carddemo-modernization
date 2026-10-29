#!/usr/bin/env bash
# Loads the sample data into $CARDDEMO_DB (default carddemo): every base file in app/data/ASCII, the 10
# USRSEC users (BCrypt hashes, accounts linked round-robin to the regular users, D-SEED), and the IMS
# pending-authorization sample. Transaction types and categories come from the ASCII trantype.txt and
# trancatg.txt (DF-1), so the Db2 module seed (DB2LTTYP/DB2LTCAT) is not loaded on top. Run on a freshly
# reset database (make db-reset); loading twice fails on duplicate keys.
set -euo pipefail
. "$(cd "$(dirname "$0")" && pwd)/stack-env.sh"
require_secrets CARDDEMO_DB_PASSWORD
app="$CARDDEMO_REPO/app"
batch load "$app/data/ASCII" \
    "usrsec=$app/data/EBCDIC/AWS.M2.CARDDEMO.USRSEC.PS" \
    "ims=$app/app-authorization-ims-db2-mq/data/EBCDIC/AWS.M2.CARDDEMO.IMSDATA.DBPAUTP0.dat"
