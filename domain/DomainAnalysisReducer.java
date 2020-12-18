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
import java.util.ArrayList;
import java.util.List;

import org.apache.avro.util.Utf8;
import org.apache.gora.mapreduce.GoraReducer;
import org.apache.gora.store.DataStore;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.Text;
import org.apache.nutch.crawl.CrawlStatus;
import org.apache.nutch.storage.StorageUtils;
import org.apache.nutch.storage.WebPage;
import org.apache.nutch.util.TableUtil;
import org.slf4j.Logger;

/**
 * Combines all WebPages with the same host key to create a Host object, with
 * some statistics.
 */
public class DomainAnalysisReducer extends
    GoraReducer<Text, WebPage, String, WebPage> {
	
	public static final Logger LOG = DomainAnalysisJob.LOG;
	public DataStore<String, WebPage> datastore;
	
	private List<WebPage> domainWebPages = new ArrayList<WebPage>();	// Cache for domain inlinks
    protected static float q1_ur_threshold = 500.0f;
    protected static float q1_ur_docCount = 50;
    public static final Utf8 Queue = new Utf8("q");		// Markers for Q1 and Q2
    public static final Utf8 Q1 = new Utf8("q1");			
    public static final Utf8 Q2 = new Utf8("q2");
    
	  @Override
	  protected void setup(Context context) throws IOException,
	  InterruptedException {
	    Configuration conf = context.getConfiguration();
	    try {
	      datastore = StorageUtils.createWebStore(conf, String.class, WebPage.class);
	    }
	    catch (ClassNotFoundException e) {
	      throw new IOException(e);
	    }
	    q1_ur_threshold = conf.getFloat("domain.queue.threshold.bytes", 500.0f);
	    q1_ur_docCount = conf.getInt("domain.queue.doc.count", 50);
	    LOG.info("Conf updated: Queue-bytes-threshold = " + q1_ur_threshold + " Queue-doc-threshold: " + q1_ur_docCount);
	  }
	  
	  @Override
	  protected void cleanup(Context context) throws IOException, InterruptedException {
	    datastore.close();
	  }

  @Override
  protected void reduce(Text key, Iterable<WebPage> values, Context context)
      throws IOException, InterruptedException {
	  
	  domainWebPages.clear();
  
	  int doc_counter = 0;
	  int total_ur_bytes = 0;

    for ( WebPage page : values ) {
    	// cache
    	domainWebPages.add( WebPage.newBuilder(page).build() );
    	
    	// do not consider those doc's that are not fetched or link URLs
        if ( page.getStatus() == CrawlStatus.STATUS_UNFETCHED ) {
       	 continue;
        }
    	
    	doc_counter++;
    	int ur_score_int = 0;
  	    int doc_ur_bytes = 0;
  	    int doc_total_bytes = 0;
    	String ur_score_str = "0";
    	String langInfo_str = null;
    	
    	// read page and find its Urdu score
    	langInfo_str = TableUtil.toString(page.getMarkers().get(new Utf8("cld2") ) );
    	ur_score_str = TableUtil.toString(page.getMarkers().get(new Utf8("usc") ));
    	if (langInfo_str == null) {
			continue;
		}
    	ur_score_int = Integer.parseInt(ur_score_str);
    	doc_total_bytes = Integer.parseInt( langInfo_str.split("&")[0] );
    	doc_ur_bytes = ( doc_total_bytes * ur_score_int) / 100;				//Formula to find ur percentage
    	
    	total_ur_bytes += doc_ur_bytes;  	
    	
    }
    float avg_bytes = 0;
    float log10 = 0;
    if ( doc_counter > 0 && total_ur_bytes > 0) {
    	avg_bytes = (float) total_ur_bytes/doc_counter;
    	 log10 = (float) Math.log10(avg_bytes);
    	 log10 = (Math.round(log10 * 100000f)/100000f);
    }
    
    context.getCounter("DomainAnalysis", "DomainCount").increment(1);
    // if average bytes and doc count, are more than threshold then mark as q1
    boolean mark = false;
    if ( avg_bytes >= q1_ur_threshold && doc_counter >= q1_ur_docCount ) {
    	mark = true;
    	LOG.info("domain-q1: " + key.toString() + " total: " + domainWebPages.size()+ " fetched: " + doc_counter +
    			" avg.ur-byte: " + avg_bytes + " score: " + log10);
    }
    
    LOG.info("domain: " + key.toString() + " total: " + domainWebPages.size()+ " fetched: " + doc_counter +
			" avg.ur-byte: " + avg_bytes + " score: " + log10);
    
    for ( WebPage page: domainWebPages) {
    	
    	CharSequence key1 = page.getMarkers().get( DomainAnalysisJob.URL_ORIG_KEY );
    	if( key1 == null) {  		
    		continue;
    	}
    	String orig_key = key1.toString(); 
    	page.setScore(log10);
    	if (mark) {
    		page.getMarkers().put( Queue, Q1);
    	}else {
    		page.getMarkers().put( Queue, Q2);			// default rule
    	}
    	page.getMarkers().put(DomainAnalysisJob.URL_ORIG_KEY, null);
    	context.write(orig_key, page);
    }
  }
}
