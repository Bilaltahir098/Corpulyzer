package org.apache.nutch.parse.html;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

import org.apache.hadoop.conf.Configuration;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class LangFinder {
	  public static final Logger LOG = LoggerFactory.getLogger(LangFinder.class);
	  
	  private static Configuration conf;
	  private static String cld2_api = null; 	// By default, it is disabled
	  private static boolean cld2_enable = false;
	  private static int cld2_timeout = 10000;  // Default timeout is 10 second (10000 millisecond)
	  
	  
	  
	  private long ur_score = 0;
	  private String langInfo = "";
	  
	  public LangFinder(Configuration cnf)
	  {
		  this.setConf(cnf);
	  }
	  public LangFinder()
	  {
		  
	  }
	  public int getUrScore()
	  {
		  return (int) this.ur_score;
	  }
	  public String getLangInfo()
	  {
		  return this.langInfo;
	  }
	  
	  @SuppressWarnings("unchecked")
	public void cld2LangFinder(String txt) throws UnsupportedEncodingException, IOException, ParseException
		  {
			  txt = URLEncoder.encode(txt, "UTF-8").replace("+", "%20");
			  JSONObject obj = new JSONObject();
			  obj.put("content", txt );
			  StringBuilder content;
			  
			  	String cld2_req = this.cld2_api;
				
				try {
					URL url = new URL(cld2_req);
					HttpURLConnection con = (HttpURLConnection) url.openConnection();
					con.setRequestMethod("POST");
					con.setDoOutput(true);
					con.setRequestProperty("User-Agent", "Java client");
		            con.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
					con.setReadTimeout( this.cld2_timeout );
					
			         try (DataOutputStream wr = new DataOutputStream(con.getOutputStream())) {
			                wr.writeBytes(obj.toString());
			                wr.flush();
			                wr.close();
			            }
					try (BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream())))
		            {
		                String line;
		                content = new StringBuilder();
		                while ((line = in.readLine()) != null) {
		                    content.append(line);
		                    content.append(System.lineSeparator());
		                }
		            }
					JSONParser parser = new JSONParser();
					JSONObject json = (JSONObject) parser.parse(content.toString());
					this.langInfo = json.get("score").toString(); 
					this.ur_score = (long) json.get("ur") ;
		             		            
//			        Close Connection
		            con.disconnect();
		            
		            
				} catch (IOException ex) {
					ex.printStackTrace();
				}
				
		  }
	  
	  
	  public void setConf(Configuration conf) {
		    this.conf = conf;
		    // ================ CLD2 section ====================
		    String cld2_str = "/cld2";
		    // Set CLD2 Configuration
		    String cld2_url  = conf.get("cld2.rest.url", "http://localhost");
		    String cld2_port = conf.get("cld2.rest.port", "6161");
		    String cld2_enable = conf.get("filter.lang.enable", "false");
		    String cld2_timeout = conf.get("cld2.rest.timeout", "10000" );
		    
		    this.cld2_enable = Boolean.parseBoolean(cld2_enable);
		    
		    if ( this.cld2_enable )
		    {
		    	this.cld2_api = cld2_url + ':' + cld2_port + cld2_str;
		    	this.cld2_timeout = Integer.parseInt(cld2_timeout);
		    	
				LOG.info("Given CLD2 API URL: " + this.cld2_api);
		    }
		    else {
				LOG.info("CLD2 is disabled. (Language Identification option)");
			}	    
			
		  }

		  /**
		   * Get the {@link Configuration} object
		   */
		  public Configuration getConf() {
		    return this.conf;
		  }
		  
		  public boolean isLangFilterEnable()
		  {
			  return this.cld2_enable;
		  }

}
