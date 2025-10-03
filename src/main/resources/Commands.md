cd /Users/apoorvlodhi/IdeaProjects/RAG-441

# 1) Rebuild the fat JAR
sbt clean assembly

# 2) Clean previous (empty) output
rm -rf /Users/apoorvlodhi/cloud/output

# 3) Run the Hadoop job to (re)build Lucene shard(s)
hadoop jar target/scala-3.5.1/*assembly*.jar mr.Driver

# 4) Sanity check: you should now see many Lucene files, not just segments_1 + write.lock
ls -lah /Users/apoorvlodhi/cloud/output/index_shard_00

# 5) Query
sbt 'runMain local.QueryCLI --debug --dir=/Users/apoorvlodhi/cloud/output --k=5 "attention is all you need"'
