#!/usr/bin/env bash
# Downloads real-world Thrift IDL files into samples/. Includes are resolved by file name inside samples/,
# so every included file is fetched next to its includer.
set -eu
cd "$(dirname "$0")/samples"

fetch() { echo "$2"; curl -sSf -o "$2" "$1"; }

# Apache Thrift repository: the tutorial (with its include), the compiler test suite and fb303
fetch https://raw.githubusercontent.com/apache/thrift/master/tutorial/tutorial.thrift        tutorial.thrift
fetch https://raw.githubusercontent.com/apache/thrift/master/tutorial/shared.thrift          shared.thrift
fetch https://raw.githubusercontent.com/apache/thrift/master/test/ThriftTest.thrift          ThriftTest.thrift
fetch https://raw.githubusercontent.com/apache/thrift/master/contrib/fb303/if/fb303.thrift  fb303.thrift

# Real-world schemas from other Apache / CNCF projects
fetch https://raw.githubusercontent.com/jaegertracing/jaeger-idl/main/thrift/jaeger.thrift                       jaeger.thrift
fetch https://raw.githubusercontent.com/apache/parquet-format/master/src/main/thrift/parquet.thrift              parquet.thrift
fetch https://raw.githubusercontent.com/apache/cassandra/cassandra-2.2/interface/cassandra.thrift               cassandra.thrift
fetch https://raw.githubusercontent.com/apache/hive/master/standalone-metastore/metastore-common/src/main/thrift/hive_metastore.thrift hive_metastore.thrift
