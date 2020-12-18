package org.apache.nutch.parse.html;
import java.util.ArrayList;

import org.apache.hadoop.conf.Configuration;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ModefiedTitle {
	 // public static final Logger LOG = LoggerFactory.getLogger(LangFinder.class);
          
		private double title1_depth;
		private double title_score,h1_score,h2_or_content_score;	
		private static Configuration conf;
		public static final Logger LOG = LoggerFactory.getLogger(ModefiedTitle.class);
	 
	  public double get_title1_depth()
          {
    	    return this.title1_depth;
          }
	 // constructor
	public ModefiedTitle(Configuration cnf)
	{
		this.setConf(cnf);
	}	


       public void setConf(Configuration conf) 
       {
         this.conf = conf;
         String title1_scr=conf.get("title1.depth.score","3,2,1");
         LOG.info("title1.depth.score: "+title1_scr);
	         if(title1_scr.split(",").length==3)
		 {
			 this.title_score=Double.parseDouble(title1_scr.split(",")[0]);
			 this.h1_score=Double.parseDouble(title1_scr.split(",")[1]);
			 this.h2_or_content_score=Double.parseDouble(title1_scr.split(",")[2]);
		 }
       }

		  /**
		   * Get the {@link Configuration} object
		   */
	  public Configuration getConf() 
	  {
	    return this.conf;
	  }

       public String add_title(String title,String text, String Rawcontent, int min_length,int max_length, String doc_url )
       {
	       String final_title="";
	       try
	       {
            String t1= Extract_urdu(title,doc_url);
			String H1=get_H1(Rawcontent,doc_url);
			H1=Extract_urdu(H1,doc_url);
			String H2=get_H2(Rawcontent,doc_url);
			H2=Extract_urdu(H2,doc_url);
			String content=get_content_as_title(text,doc_url);
			content=Extract_urdu(text,doc_url);

	        if(t1.length()>min_length)
	        {
                 final_title=t1;
		 this.title1_depth=this.title_score;
	        }
		else if(H1.length()>min_length || H2.length()>max_length)
		{
         	if(H1.length()>min_length)
		 {
			  final_title=H1;
			  this.title1_depth=this.h1_score;
		 }
		 else if(H2.length()>min_length)
		 {
	          final_title=H2;
	          this.title1_depth=this.h2_or_content_score;
		 }
		}
		else if(content.length()>min_length)
		{
			final_title=content;
			this.title1_depth=this.h2_or_content_score;
			
			if ( LOG.isDebugEnabled() ) {
				LOG.info("title1==content");
			}
		}

		if(final_title.length()>max_length)
		{
			final_title=final_title.substring(0,max_length);
                        if(final_title.lastIndexOf(' ')>0)
                        {
                        final_title=final_title.substring(0,final_title.lastIndexOf(' '));
                        }

		}

	       }
	       catch(Exception ex)
	       {
		  final_title="";
		  LOG.error("Exception",ex);
	       }
	       return final_title;
       }


    private static String get_H1(String data, String doc_url)
    {
    String H1="";
    try
    {
    Elements el;
    Document doc=Jsoup.parse(data);
    el=doc.body().getElementsByTag("h1");
    if(!el.isEmpty())
    {
    H1=el.first().text();
    }
    }
    catch(Exception ex)
    {
    H1="";
   LOG.error("Exception URL:"+doc_url,ex);
    }
    return H1;
    }


   private static String get_H2(String data, String doc_url)
    {
	   String H2="";
    try
    {
	    Elements el;
	    Document doc=Jsoup.parse(data);
	    el=doc.body().getElementsByTag("h2");
    if(!el.isEmpty())
    {
    	H2=el.first().text();
    }
    }
    catch(Exception ex)
    {
	    H2="";
	    LOG.error("Exception URL:"+doc_url,ex);
    }
    	return H2;
    } 


   private static String get_content_as_title(String data, String doc_url)
   {
	   String str="";
	try
	{
		str=Extract_urdu(data, doc_url);
	}
	catch(Exception ex)
	{
		str="";
		LOG.error("Exception",ex);
	}
	return str;
    }
       private static String Extract_urdu(String data, String doc_url) {
                
        data=data.replaceAll("\"", " ");
        data=data.replaceAll("'"," ");
        data=data.replaceAll("’"," ");
        data=data.replaceAll("‘"," ");
        data=data.replaceAll("۔"," ");
        data=data.replaceAll("’’","");
        data=data.replaceAll("‘‘",""); 
        data=data.replaceAll("–"," ");
        data=data.replaceAll("-"," ");
        data=data.replaceAll("\\(", " ");
        data=data.replaceAll("\\)", " ");
        data=data.replaceAll("\\{", " ");
        data=data.replaceAll("\\}", " ");
        data=data.replaceAll("\\[", " ");
        data=data.replaceAll("\\]", " ");
        data=data.trim();
		String[]s=data.split(" ");
		ArrayList<String>updated_list=Urdu_tokens_list(s, doc_url);
		String out_put="";
		try
		{
		for(String ss:updated_list)
		{
		 out_put+=ss+" ";	
		}
		}
		catch(Exception ex)
		{
		 LOG.error("Exception",ex);
		}
		return out_put;
	}


       private static ArrayList<String> Urdu_tokens_list(String[] s, String doc_url) 
       {
		ArrayList<String>urdu_tokens=new ArrayList<>();
		ArrayList<TokenObjectClass>obj_list=new ArrayList<>();
		try
		{
		for(int i=0;i<s.length;i++)
		{
			obj_list.add(urdu_status(s[i], doc_url));
		}

		for(int i=0;i<obj_list.size();i++)
		{
			//first index
		    if(i==0&&obj_list.size()>1)
			{
				if(obj_list.get(i).Isurdu || (obj_list.get(i).IsDigit && (obj_list.get(i+1).Isurdu||obj_list.get(i+1).IsDigit)))
				{
					urdu_tokens.add(obj_list.get(i).value);
				}
			}
		    //last index
		    else if(i+1==obj_list.size())
			{
				if(obj_list.get(i).Isurdu)
				{
					urdu_tokens.add(obj_list.get(i).value);
				}
				else
				{
					// if a single word or urdu exist
					// it would be discarded at next level so so i discarded it here
					if(obj_list.size()>1)
					{
						if(obj_list.get(i).IsDigit && (obj_list.get(i-1).Isurdu || obj_list.get(i-1).IsDigit))
						{
							urdu_tokens.add(obj_list.get(i).value);
						}
					}
				}
			}
			else
			{
				String sss=obj_list.get(i).value;
				sss=sss+"";

				if(obj_list.get(i).Isurdu ||(obj_list.get(i).IsDigit &&  ((obj_list.get(i+1).Isurdu||obj_list.get(i+1).IsDigit)&&(obj_list.get(i-1).Isurdu||obj_list.get(i-1).IsDigit))))
			    {
				 urdu_tokens.add(obj_list.get(i).value);
			    }
			}
		}
		}
		catch(Exception ex)
		{
		 LOG.error("Exception URL:"+doc_url,ex);
		}
		return urdu_tokens;

	}

       private static TokenObjectClass urdu_status(String s, String doc_url) {
		TokenObjectClass p=new TokenObjectClass();
		try
		{
			int Lower_range   = 1536;   //x0600
		    int Upper_range = 1791;   //x06FF
		    int Number_lower = 48;    //x30
		    int Number_upper = 57;    //x39
			char[]c=s.toCharArray();
	        // the first character of a language is much important.
			// the whole token belong to the language to which first character belongs.
			int char_value=0;
			if(c.length>0)
			{
            char_value = (int)c[0];
			}
            if((char_value >= Lower_range && char_value <= Upper_range))
            {
				p.value=s;
				p.Isurdu=true;
				p.IsDigit=false;
			}
            else if(char_value >= Number_lower && char_value <= Number_upper)
            {
            	                p.value=s;
				p.Isurdu=false;
				p.IsDigit=true;
            }
			else
			{
				p.value=s;
				p.Isurdu=false;
				p.IsDigit=false;
			}

		}
		catch (Exception ex)
		{
			LOG.error("URL: "+doc_url,ex);
			p.value=s;
			p.Isurdu=false;
			p.IsDigit=false;
		}
		return p;

	}
}

