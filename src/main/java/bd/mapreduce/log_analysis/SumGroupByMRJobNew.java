package bd.mapreduce.log_analysis;

import bd.io.AdMetricWritable;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;

import java.io.IOException;

/**
 * @Auther : guojianmin
 * @Date : 2019/5/16 08:05
 * @Description : 需求2 自定义value
 *
 * 统计2019年1月 1号到7号 每天的曝光量，点击量和点击率
 *
 * 日志文件名 ：date(例如：20190107)
 * 等价table 字段： id , advertiser_id , duration , position , area_id , terminal_id , view_type , device_id , date
 * 等价sql : select date,sum(view_type=1) pv , sum(view_type=2)click, click/pv as clickRate
 *          from log_table where date >= ‘20190101’ and date <='20190107'
 *
 * 实现思路：map 阶段： 拆分每条日志数据，将需要的date，和view_type 找出来放在Entity中 ，然后以<date,Entity>发送给reduce
 *          reduce 阶段：同一个reduce的shuffle阶段按照日期排序，并且按照日期分组，每一组值调用一次reduce进行统计，然后输出统计结果<date,Entity>
 */
public class SumGroupByMRJobNew extends Configured implements Tool {
    @Override
    public int run(String[] args) throws Exception {
        //1.配置job
        Configuration conf = this.getConf();
        Job job = null;

        //2.创建job
        job = Job.getInstance(conf);
        job.setJarByClass(SumGroupByMRJobNew.class);//设置通过主类来获取job

        //3.给job设置执行流程
        //3.1 HDFS中需要处理的文件路径
        Path path = new Path(args[0]);
        //job添加输入路径
        FileInputFormat.addInputPath(job, path);

        //3.2设置map执行流程
        job.setMapperClass(SumGroupByMapper.class);
        job.setMapOutputKeyClass(Text.class);//设置map输出key的类型
        job.setMapOutputValueClass(AdMetricWritable.class);//设置map输出value的类型

        //3.2设置reduce执行流程
        job.setReducerClass(SumGroupByReducer.class);
        job.setOutputKeyClass(Text.class);//设置reduce输出key的类型
        job.setOutputValueClass(AdMetricWritable.class);//设置reduce输出value的类型

        //job.setNumReduceTasks(3);//硬编码，不友好，建议通过传参的方式实现

        //3.4设置计算结果输出路径
        Path output = new Path(args[1]);
        FileOutputFormat.setOutputPath(job, output);

        //4.提交job，并等待job执行完成
        boolean result = job.waitForCompletion(true);//等待job执行完成
        return result ? 0 : 1;
    }

    //map阶段

    /***
     * 输入数据键值对类型
     * LongWritable : 输入数据的偏移量
     * Text：输入数据类型
     *
     * 输出数据键值对类型
     * Text：输出数据key的类型
     * AdMetricWritable：输出数据value类型
     */
    public static class SumGroupByMapper extends Mapper<LongWritable, Text, Text, AdMetricWritable> {

        @Override
        protected void map(LongWritable key, Text value, Context context) throws IOException, InterruptedException {
            //id , advertiser_id , duration , position , area_id , terminal_id , view_type , device_id , date
            String line = value.toString();
            String[] fields = line.split("\t");

            String date = fields[8];
            String viewType = fields[6];
            if (viewType != null && !viewType.equals("")){
                AdMetricWritable adMetric = new AdMetricWritable();
                int viewTypeInt = Integer.parseInt(viewType);
                if (viewTypeInt == 1){//曝光
                    adMetric.setPv(1);
                }else if (viewTypeInt == 2){
                    adMetric.setClick(1);
                }
                context.write(new Text(date),adMetric);
            }

        }
    }

    //Reduce阶段

    /**
     * 输入数据键值对类型
     * Text:
     * AdMetricWritable:
     * <p>
     * 输出数据键值对类型
     * Text:
     * ntWritable:
     */
    public static class SumGroupByReducer extends Reducer<Text, AdMetricWritable, Text, AdMetricWritable> {
        @Override
        protected void reduce(Text key, Iterable<AdMetricWritable> values, Context context) throws IOException, InterruptedException {
            long pv = 0;
            long click = 0;
            float clickRate = 0;
            for (AdMetricWritable adMetric : values){
                pv += adMetric.getPv();
                click += adMetric.getClick();
            }
            //clickRate = clisk / pv
            if (pv != 0 && click != 0){
                clickRate = (float) click / (float) pv;
            }
            AdMetricWritable ad = new AdMetricWritable(pv,click,clickRate);
            context.write(key,ad);
        }
    }

    public static void main(String[] args) {

        //用于本地测试
        if (args.length == 0){
            args = new String[]{
                "hdfs://ns/mr_project/ad_log/",
                "hdfs://ns/mr_project/log_analysis/output2"
            };
        }
        Configuration conf = new Configuration();
        Path hdfsOutPutPath = new Path(args[1]);
        try {
            //如果输出路径存在，则删除
            FileSystem fileSystem = FileSystem.get(conf);
            if (fileSystem.exists(hdfsOutPutPath)){
                fileSystem.delete(hdfsOutPutPath,true);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        try {
            int status = ToolRunner.run(conf, new SumGroupByMRJobNew(), args);
            System.exit(status);
        } catch (Exception e) {
            e.printStackTrace();
        }
        //hadoop jar xxxx.jar mainclass -Dmapreduce.job.reduce=3    设置reduce的数量

    }
}
