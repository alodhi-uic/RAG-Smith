# EMR submit notes (sketch)
- Build fat jar (with sbt-assembly if added).
- Upload to S3.
- Create EMR cluster (EMR 6.x).
- Add Hadoop jar step with your driver main class and args.
