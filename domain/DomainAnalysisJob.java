/*******************************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package org.apache.nutch.domain;

import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;

import org.apache.avro.util.Utf8;
import org.apache.gora.mapreduce.GoraMapper;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.apache.nutch.metadata.Nutch;
import org.apache.nutch.net.URLFilters;
import org.apache.nutch.net.URLNormalizers;
import org.apache.nutch.scoring.ScoringFilters;
import org.apache.nutch.storage.StorageUtils;
import org.apache.nutch.storage.WebPage;
import org.apache.nutch.util.NutchConfiguration;
import org.apache.nutch.util.NutchJob;
import org.apache.nutch.util.TableUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Scans the web table and create host entries for each unique host.
 * 
 * 
 **/

public class DomainAnalysisJob implements Tool {

  public static final Logger LOG = LoggerFactory
      .getLogger(DomainAnalysisJob.class);
  private static final Collection<WebPage.Field> FIELDS = new HashSet<WebPage.Field>();

  private Configuration conf;
  protected static final Utf8 URL_ORIG_KEY = new Utf8("doc_orig_id");
  protected static final Utf8 DOC_DUMMY_MARKER = new Utf8("doc_marker");
  protected static final Utf8 DUMMY_KEY = new Utf8("doc_id");
  protected static final Utf8 DOMAIN_DUMMY_MARKER = new Utf8("domain_marker");
  protected static final Utf8 LINK_MARKER = new Utf8("link");
  protected static final Utf8 Queue = new Utf8("q");
  
  private static URLNormalizers urlNormalizers;
  private static URLFilters filters;
  private static int maxURL_Length;

  static {
    FIELDS.add(WebPage.Field.STATUS);
    FIELDS.add(WebPage.Field.LANG_INFO);
    FIELDS.add(WebPage.Field.URDU_SCORE);
    FIELDS.add(WebPage.Field.MARKERS);
    FIELDS.add(WebPage.Field.INLINKS);
  }

  /**
   * Maps each WebPage to a host key.
   */
  public static class Mapper extends GoraMapper<String, WebPage, Text, WebPage> {
	  
	  @Override
	    protected void setup(Context context) throws IOException ,InterruptedException {
		  Configuration conf = context.getConfiguration();
		  urlNormalizers = new URLNormalizers(context.getConfiguration(), URLNormalizers.SCOPE_DEFAULT);
		  filters = new URLFilters(context.getConfiguration());
		  maxURL_Length = conf.getInt("url.characters.max.length", 2000);
	    }

    @Override
    protected void map(String key, WebPage page, Context context)
        throws IOException, InterruptedException {
     
     String reversedHost = null;
     if (page == null) {
    	 return;
     }
 	if ( key.length() > maxURL_Length ) {
		return;
	}
     String url = null;
     try {
    	 url = TableUtil.unreverseUrl(key);
         url = urlNormalizers.normalize(url, URLNormalizers.SCOPE_DEFAULT);
         url = filters.filter(url); // filter the url
       } catch (Exception e) {
         LOG.warn("Skipping " + key + ":" + e);
         return;
       }
     if ( url == null) {
    	 context.getCounter("DomainAnalysis", "FilteredURL").increment(1);
    	 return;
     }
     try {
    	 reversedHost = TableUtil.getReversedHost(key.toString());
     } 
     catch (Exception e) {
		return;
	}
     page.getMarkers().put( URL_ORIG_KEY, new Utf8(key) );
     
     context.write( new Text(reversedHost), page );
     
    }
  }

  public DomainAnalysisJob() {
  }

  public DomainAnalysisJob(Configuration conf) {
    setConf(conf);
  }

  @Override
  public Configuration getConf() {
    return conf;
  }

  @Override
  public void setConf(Configuration conf) {
    this.conf = conf;
   }

  public void updateDomains(boolean buildLinkDb, int numTasks) throws Exception {
	  
	  
    NutchJob job = NutchJob.getInstance(getConf(), "rankDomain-update");
    
    job.getConfiguration().setInt("mapreduce.task.timeout", 1800000);

//    int reducer_mem = 3072;
//    String heap_percentage = "-Xmx" + (int) (reducer_mem * 0.8)+ "m";
//    job.getConfiguration().setInt("mapreduce.reduce.memory.mb", reducer_mem);
//    job.getConfiguration().set("mapreduce.reduce.java.opts", heap_percentage );
    
    if ( numTasks < 1) {
        job.setNumReduceTasks(job.getConfiguration().getInt(
            "mapred.map.tasks", job.getNumReduceTasks()));
      } else {
        job.setNumReduceTasks(numTasks);
      }
    ScoringFilters scoringFilters = new ScoringFilters(getConf());
    HashSet<WebPage.Field> fields = new HashSet<WebPage.Field>(FIELDS);
    fields.addAll(scoringFilters.getFields());
    
    StorageUtils.initMapperJob(job, fields, Text.class, WebPage.class,
    		Mapper.class);
    StorageUtils.initReducerJob(job, DomainAnalysisReducer.class);
    
    
    job.waitForCompletion(true);
  }

  @Override
  public int run(String[] args) throws Exception {
    boolean linkDb = false;
    int numTasks = -1;
    for (int i = 0; i < args.length; i++) {
      if ("-rankDomain".equals(args[i])) {
        linkDb = true;
      } else if ("-crawlId".equals(args[i])) {
        getConf().set(Nutch.CRAWL_ID_KEY, args[++i]);
      } else if ("-numTasks".equals(args[i]) ) {
    	  numTasks = Integer.parseInt(args[++i]);
      }
      else {
        throw new IllegalArgumentException("unrecognized arg " + args[i]
            + " usage: updatedomain -crawlId <crawlId> [-numTasks N]" );
      }
    }
    LOG.info("Updating DomainRank:");
    updateDomains(linkDb, numTasks);
    return 0;
  }

  public static void main(String[] args) throws Exception {
    final int res = ToolRunner.run(NutchConfiguration.create(),
        new DomainAnalysisJob(), args);
    System.exit(res);
  }
}
