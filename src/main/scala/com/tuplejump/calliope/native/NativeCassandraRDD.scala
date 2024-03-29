package com.tuplejump.calliope.native

import scala.reflect.ClassTag
import org.apache.spark._
import com.tuplejump.calliope.CasBuilder
import org.apache.spark.rdd.RDD
import com.tuplejump.calliope.utils.{CassandraPartition, SparkHadoopMapReduceUtil}
import org.apache.hadoop.mapreduce.{InputSplit, TaskAttemptID, JobID}
import java.text.SimpleDateFormat
import java.util.Date
import com.tuplejump.calliope.hadoop.cql3.CqlInputFormat
import com.datastax.driver.core.Row
import scala.collection.JavaConversions._


private[calliope] class NativeCassandraRDD[T: ClassTag](sc: SparkContext,
                                                        @transient cas: CasBuilder,
                                                        unmarshaller: Row => T)
  extends RDD[T](sc, Nil)
  with SparkHadoopMapReduceUtil
  with Logging {

  // A Hadoop Configuration can be about 10 KB, which is pretty big, so broadcast it
  @transient private val hadoopConf = cas.configuration
  private val confBroadcast = sc.broadcast(new SerializableWritable(hadoopConf))

  @transient val jobId = new JobID(System.currentTimeMillis().toString, id)

  private val jobtrackerId: String = {
    val formatter = new SimpleDateFormat("yyyyMMddHHmm")
    formatter.format(new Date())
  }

  def compute(theSplit: Partition, context: TaskContext): Iterator[T] = new Iterator[T] {
    val conf = confBroadcast.value.value
    val format = new CqlInputFormat
    val split = theSplit.asInstanceOf[CassandraPartition]
    logInfo("Input split: " + split.inputSplit)

    //Set configuration
    val attemptId = new TaskAttemptID(jobtrackerId, id, true, split.index, 0)
    val hadoopAttemptContext = newTaskAttemptContext(conf, attemptId)


    logInfo(s"Will create record reader for ${format}")
    val reader = format.createRecordReader(split.inputSplit.value, hadoopAttemptContext)

    reader.initialize(split.inputSplit.value, hadoopAttemptContext)
    context.addOnCompleteCallback(() => close())

    var havePair = false
    var finished = false

    override def hasNext: Boolean = {
      if (!finished && !havePair) {
        finished = !reader.nextKeyValue
        havePair = !finished
      }
      !finished
    }

    override def next: T = {
      if (!hasNext) {
        throw new java.util.NoSuchElementException("End of stream")
      }
      havePair = false

      val row = reader.getCurrentValue
      row.getColumnDefinitions.asList().map {
        cd =>
          cd.getType
      }

      unmarshaller(reader.getCurrentValue)
    }

    private def close() {
      try {
        reader.close()
      } catch {
        case e: Exception => logWarning("Exception in RecordReader.close()", e)
      }
    }
  }

  def getPartitions: Array[Partition] = {

    logInfo("Building partitions")
    val jc = newJobContext(hadoopConf, jobId)
    val inputFormat = new CqlInputFormat
    val rawSplits = inputFormat.getSplits(jc).toArray
    val result = new Array[Partition](rawSplits.size)
    for (i <- 0 until rawSplits.size) {
      result(i) = new CassandraPartition(id, i, rawSplits(i).asInstanceOf[InputSplit])
    }
    logInfo(s"Got ${result.length} partitions ")
    result
  }

  override protected[calliope] def getPreferredLocations(split: Partition): Seq[String] = {
    split.asInstanceOf[CassandraPartition].s.getLocations
  }
}
