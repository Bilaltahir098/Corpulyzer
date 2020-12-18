/**
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
 */

package org.apache.nutch.parse.html;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.avro.util.Utf8;
import org.apache.hadoop.conf.Configuration;
import org.apache.html.dom.HTMLDocumentImpl;
import org.apache.nutch.metadata.Metadata;
import org.apache.nutch.metadata.Nutch;
import org.apache.nutch.parse.HTMLMetaTags;
import org.apache.nutch.parse.Outlink;
import org.apache.nutch.parse.Parse;
import org.apache.nutch.parse.ParseFilters;
import org.apache.nutch.parse.ParseStatusCodes;
import org.apache.nutch.parse.ParseStatusUtils;
import org.apache.nutch.parse.Parser;
import org.apache.nutch.storage.ParseStatus;
import org.apache.nutch.storage.WebPage;
import org.apache.nutch.util.Bytes;
import org.apache.nutch.util.EncodingDetector;
import org.apache.nutch.util.NutchConfiguration;
import org.apache.nutch.util.TableUtil;
import org.cyberneko.html.parsers.DOMFragmentParser;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.DOMException;
import org.w3c.dom.DocumentFragment;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import de.l3s.boilerpipe.BoilerpipeProcessingException;
import de.l3s.boilerpipe.document.TextDocument;
import de.l3s.boilerpipe.extractors.CommonExtractors;
import de.l3s.boilerpipe.sax.BoilerpipeSAXInput;
import de.l3s.boilerpipe.sax.HTMLDocument;
import de.l3s.boilerpipe.sax.HTMLHighlighter;

public class HtmlParser implements Parser {
  public static final Logger LOG = LoggerFactory
      .getLogger("org.apache.nutch.parse.html");

  // I used 1000 bytes at first, but found that some documents have
  // meta tag well past the first 1000 bytes.
  // (e.g. http://cn.promo.yahoo.com/customcare/music.html)
  // NUTCH-2042 (cf. TIKA-357): increased to 8 kB
  private static final int CHUNK_SIZE = 8192;

  // NUTCH-1006 Meta equiv with single quotes not accepted
  private static Pattern metaPattern = Pattern.compile(
      "<meta\\s+([^>]*http-equiv=(\"|')?content-type(\"|')?[^>]*)>",
      Pattern.CASE_INSENSITIVE);
  private static Pattern charsetPattern = Pattern.compile(
      "charset=\\s*([a-z][_\\-0-9a-z]*)", Pattern.CASE_INSENSITIVE);
  private static Pattern charsetPatternHTML5 = Pattern.compile(
      "<meta\\s+charset\\s*=\\s*[\"']?([a-z][_\\-0-9a-z]*)[^>]*>",
      Pattern.CASE_INSENSITIVE);

  private static Collection<WebPage.Field> FIELDS = new HashSet<WebPage.Field>();

  static {
    FIELDS.add(WebPage.Field.BASE_URL);
  }

  private String parserImpl;

  /**
   * Given a <code>ByteBuffer</code> representing an html file of an
   * <em>unknown</em> encoding, read out 'charset' parameter in the meta tag
   * from the first <code>CHUNK_SIZE</code> bytes. If there's no meta tag for
   * Content-Type or no charset is specified, the content is checked for a
   * Unicode Byte Order Mark (BOM). This will also cover non-byte oriented
   * character encodings (UTF-16 only). If no character set can be determined,
   * <code>null</code> is returned. <br />
   * See also
   * http://www.w3.org/International/questions/qa-html-encoding-declarations,
   * http://www.w3.org/TR/2011/WD-html5-diff-20110405/#character-encoding, and
   * http://www.w3.org/TR/REC-xml/#sec-guessing <br />
   * 
   * @param content
   *          <code>ByteBuffer</code> representation of an html file
   */

  private static String sniffCharacterEncoding(ByteBuffer content) {
    int length = Math.min(content.remaining(), CHUNK_SIZE);

    // We don't care about non-ASCII parts so that it's sufficient
    // to just inflate each byte to a 16-bit value by padding.
    // For instance, the sequence {0x41, 0x82, 0xb7} will be turned into
    // {U+0041, U+0082, U+00B7}.
    String str = new String(content.array(), content.arrayOffset()
        + content.position(), length, StandardCharsets.US_ASCII);

    Matcher metaMatcher = metaPattern.matcher(str);
    String encoding = null;
    if (metaMatcher.find()) {
      Matcher charsetMatcher = charsetPattern.matcher(metaMatcher.group(1));
      if (charsetMatcher.find())
        encoding = new String(charsetMatcher.group(1));
    }
    if (encoding == null) {
      // check for HTML5 meta charset
      metaMatcher = charsetPatternHTML5.matcher(str);
      if (metaMatcher.find()) {
        encoding = new String(metaMatcher.group(1));
      }
    }
    if (encoding == null) {
      // check for BOM
      if (length >= 3 && content.get(0) == (byte) 0xEF
          && content.get(1) == (byte) 0xBB && content.get(2) == (byte) 0xBF) {
        encoding = "UTF-8";
      } else if (length >= 2) {
        if (content.get(0) == (byte) 0xFF && content.get(1) == (byte) 0xFE) {
          encoding = "UTF-16LE";
        } else if (content.get(0) == (byte) 0xFE
            && content.get(1) == (byte) 0xFF) {
          encoding = "UTF-16BE";
        }
      }
    }

    return encoding;
  }

  private String defaultCharEncoding;

  private Configuration conf;

  private DOMContentUtils utils;

  private ParseFilters htmlParseFilters;

  private String cachingPolicy;
  private LangFinder langFinder;
  private String targetLanguage;
  private boolean useModifiedBoilerPipe;
  private boolean useModifiedTitle;
  private int title_min_length =0;
  private int title_max_length =0;
  private  ModefiedTitle modifiedTitle;

  public Parse getParse(String url, WebPage page) {
    HTMLMetaTags metaTags = new HTMLMetaTags();

    String baseUrl = TableUtil.toString(page.getBaseUrl());
    URL base;
    try {
      base = new URL(baseUrl);
    } catch (MalformedURLException e) {
      return ParseStatusUtils.getEmptyParse(e, getConf());
    }

    String text = "";
    String title = "";
    Outlink[] outlinks = new Outlink[0];
    boolean boilerpipeOk = false;
    boolean ModifiedBPWorks = true;
    final HTMLDocument htmlDoc = new HTMLDocument(page.getContent().array(), Charset.forName("utf-8"));
    
    // customized boilerpipe
    if ( useModifiedBoilerPipe ) {
    	final HTMLHighlighter hh = HTMLHighlighter.newExtractingInstance();
    	hh.setBoldText_threshhold(50);
		hh.setStoryLength_threshhold(500);
		hh.setMultipleStories(true);
		boilerpipeOk = true;
		try {
			text= hh.getCustomArticle(htmlDoc);
		} catch (Exception e) {
			ModifiedBPWorks = false;
			LOG.info("ModifiedBPError: " + e  + " " + baseUrl);
		}
    }
    // default boilerpipe
    else if ( !useModifiedBoilerPipe || !ModifiedBPWorks)
    {
    	try {
    		TextDocument doc = new BoilerpipeSAXInput(htmlDoc.toInputSource()).getTextDocument();
    		text = CommonExtractors.ARTICLE_EXTRACTOR.getText(doc).trim().replaceAll("\\s+", " ");
    		boilerpipeOk = true;
    	} catch (BoilerpipeProcessingException | SAXException e1) {
    		LOG.info("Boilerpipe Error: " + baseUrl + "\t" + e1 );
    	}
    }
	
	
    // parse the content
    DocumentFragment root;
    try {
      ByteBuffer contentInOctets = page.getContent();
      InputSource input = new InputSource(new ByteArrayInputStream(
          contentInOctets.array(), contentInOctets.arrayOffset()
              + contentInOctets.position(), contentInOctets.remaining()));

      EncodingDetector detector = new EncodingDetector(conf);
      detector.autoDetectClues(page, true);
      detector.addClue(sniffCharacterEncoding(contentInOctets), "sniffed");
      String encoding = detector.guessEncoding(page, defaultCharEncoding);

      page.getMetadata().put(new Utf8(Metadata.ORIGINAL_CHAR_ENCODING),
          ByteBuffer.wrap(Bytes.toBytes(encoding)));
      page.getMetadata().put(new Utf8(Metadata.CHAR_ENCODING_FOR_CONVERSION),
          ByteBuffer.wrap(Bytes.toBytes(encoding)));

      input.setEncoding(encoding);
      if (LOG.isTraceEnabled()) {
        LOG.trace("Parsing...");
      }
      root = parse(input);
    } catch (IOException e) {
      LOG.error("Failed with the following IOException: ", e);
      return ParseStatusUtils.getEmptyParse(e, getConf());
    } catch (DOMException e) {
      LOG.error("Failed with the following DOMException: ", e);
      return ParseStatusUtils.getEmptyParse(e, getConf());
    } catch (SAXException e) {
      LOG.error("Failed with the following SAXException: ", e);
      return ParseStatusUtils.getEmptyParse(e, getConf());
    } catch (Exception e) {
      LOG.error("Failed with the following Exception: ", e);
      return ParseStatusUtils.getEmptyParse(e, getConf());
    }

    // get meta directives
    HTMLMetaProcessor.getMetaTags(metaTags, root, base);
    if (LOG.isTraceEnabled()) {
      LOG.trace("Meta tags for " + base + ": " + metaTags.toString());
    }
    // check meta directives
    if (!metaTags.getNoIndex()) { // okay to index
      StringBuilder sb = new StringBuilder();
      if (LOG.isTraceEnabled()) {
        LOG.trace("Getting text...");
      }
      utils.getText(sb, root); // extract text
      if ( ! boilerpipeOk ) {
    	  text = sb.toString();
      }
      
      sb.setLength(0);
      if (LOG.isTraceEnabled()) {
        LOG.trace("Getting title...");
      }
      utils.getTitle(sb, root); // extract title
      title = sb.toString().trim();
      // customized title here
      if (useModifiedTitle) {
    	  try {
    		  ByteBuffer rawContent = page.getContent();
      		  String rawCnt = Bytes.toString(rawContent.array());
    		  String title1=modifiedTitle.add_title(title,text, rawCnt ,this.title_min_length,this.title_max_length,baseUrl);
    		  
    			if( title1.length()>this.title_max_length ) {
    				title1=title1.substring(0,this.title_max_length);
    				
    				if(title1.lastIndexOf(' ')>0) {
    					title1=title1.substring(0,title1.lastIndexOf(' '));
    				}
    			}
    			String title1_depth = String.valueOf(modifiedTitle.get_title1_depth());
    			// save to db
    			page.getMarkers().put(new Utf8("t1"), new Utf8(title1  ));
    			page.getMarkers().put(new Utf8("t1l"), new Utf8(title1_depth));
		} catch (Exception e) {
			if (LOG.isDebugEnabled()) {
				LOG.info("Title1 exception: " + e + " " + baseUrl);
			}
		}
      }

    }

    if (!metaTags.getNoFollow()) { // okay to follow links
      ArrayList<Outlink> l = new ArrayList<Outlink>(); // extract outlinks
      URL baseTag = utils.getBase(root);
      if (LOG.isTraceEnabled()) {
        LOG.trace("Getting links...");
      }
      utils.getOutlinks(baseTag != null ? baseTag : base, l, root);
      outlinks = l.toArray(new Outlink[l.size()]);
      if (LOG.isTraceEnabled()) {
        LOG.trace("found " + outlinks.length + " outlinks in " + url);
      }
    }
	// language filter
		boolean targetLangExist = false;
		int langPercentage = 0;
		int langBytes = 0;
		try {
			langFinder.cld2LangFinder(text);
		} catch (IOException | ParseException e) {
			e.printStackTrace();
		} //strippedText
		String langDetails = langFinder.getLangInfo();
		
		String[] details = langDetails.split("&");
		int count = 0;
		long doc_content_length = 0;
		
		for (String record : details) {
			count++;
			if (count == 1) {
				doc_content_length = Long.parseLong(record);
				continue;
			}
			String[] detail = record.split("-");
			if ( detail[0].toLowerCase().equals(targetLanguage) ) {
				targetLangExist = true;
				langPercentage = Integer.parseInt( detail[1] );
				langBytes    = (int) (( doc_content_length * langPercentage ) / 100 );
				break;
			}
		}
		if ( !targetLangExist && LOG.isDebugEnabled()) {
			LOG.warn("LangNotExist: Lang: " + targetLanguage + " " + langDetails + " " + url );
		}
		
		page.getMarkers().put(new Utf8("cld2"), new Utf8(langDetails)  );
		page.getMarkers().put(new Utf8("usc"), new Utf8(Integer.toString(langPercentage)  ));
		page.getMarkers().put(new Utf8("lb"), new Utf8(Integer.toString(langBytes)  ));

    ParseStatus status = ParseStatus.newBuilder().build();
    status.setMajorCode((int) ParseStatusCodes.SUCCESS);
    if (metaTags.getRefresh()) {
      status.setMinorCode((int) ParseStatusCodes.SUCCESS_REDIRECT);
      status.getArgs().add(new Utf8(metaTags.getRefreshHref().toString()));
      status.getArgs().add(
          new Utf8(Integer.toString(metaTags.getRefreshTime())));
    }

    Parse parse = new Parse(text, title, outlinks, status);
    parse = htmlParseFilters.filter(url, page, parse, metaTags, root);

    if (metaTags.getNoCache()) { // not okay to cache
      page.getMetadata().put(new Utf8(Nutch.CACHING_FORBIDDEN_KEY),
          ByteBuffer.wrap(Bytes.toBytes(cachingPolicy)));
    }

    return parse;
  }

  private DocumentFragment parse(InputSource input) throws Exception {
    if (parserImpl.equalsIgnoreCase("tagsoup"))
      return parseTagSoup(input);
    else
      return parseNeko(input);
  }

  private DocumentFragment parseTagSoup(InputSource input) throws Exception {
    HTMLDocumentImpl doc = new HTMLDocumentImpl();
    DocumentFragment frag = doc.createDocumentFragment();
    DOMBuilder builder = new DOMBuilder(doc, frag);
    org.ccil.cowan.tagsoup.Parser reader = new org.ccil.cowan.tagsoup.Parser();
    reader.setContentHandler(builder);
    reader.setFeature(org.ccil.cowan.tagsoup.Parser.ignoreBogonsFeature, true);
    reader.setFeature(org.ccil.cowan.tagsoup.Parser.bogonsEmptyFeature, false);
    reader
        .setProperty("http://xml.org/sax/properties/lexical-handler", builder);
    reader.parse(input);
    return frag;
  }

  private DocumentFragment parseNeko(InputSource input) throws Exception {
    DOMFragmentParser parser = new DOMFragmentParser();
    try {
      parser
          .setFeature(
              "http://cyberneko.org/html/features/scanner/allow-selfclosing-iframe",
              true);
      parser.setFeature("http://cyberneko.org/html/features/augmentations",
          true);
      parser.setProperty(
          "http://cyberneko.org/html/properties/default-encoding",
          defaultCharEncoding);
      parser
          .setFeature(
              "http://cyberneko.org/html/features/scanner/ignore-specified-charset",
              true);
      parser
          .setFeature(
              "http://cyberneko.org/html/features/balance-tags/ignore-outside-content",
              false);
      parser.setFeature(
          "http://cyberneko.org/html/features/balance-tags/document-fragment",
          true);
      parser.setFeature("http://cyberneko.org/html/features/report-errors",
          LOG.isTraceEnabled());
    } catch (SAXException e) {
    }
    // convert Document to DocumentFragment
    HTMLDocumentImpl doc = new HTMLDocumentImpl();
    doc.setErrorChecking(false);
    DocumentFragment res = doc.createDocumentFragment();
    DocumentFragment frag = doc.createDocumentFragment();
    parser.parse(input, frag);
    res.appendChild(frag);

    try {
      while (true) {
        frag = doc.createDocumentFragment();
        parser.parse(input, frag);
        if (!frag.hasChildNodes())
          break;
        if (LOG.isInfoEnabled()) {
          LOG.info(" - new frag, " + frag.getChildNodes().getLength()
              + " nodes.");
        }
        res.appendChild(frag);
      }
    } catch (Exception x) {
      LOG.error("Failed with the following Exception: ", x);
    }
    ;
    return res;
  }

  public void setConf(Configuration conf) {
    this.conf = conf;
    this.htmlParseFilters = new ParseFilters(getConf());
    this.parserImpl = getConf().get("parser.html.impl", "neko");
    this.defaultCharEncoding = getConf().get(
        "parser.character.encoding.default", "windows-1252");
    this.utils = new DOMContentUtils(conf);
    this.cachingPolicy = getConf().get("parser.caching.forbidden.policy",
        Nutch.CACHING_FORBIDDEN_CONTENT);
    this.langFinder = new LangFinder(conf);
    this.targetLanguage = conf.get("filter.lang.label", "urdu").toLowerCase();
    this.useModifiedBoilerPipe = conf.getBoolean("boilerpipe.modified.version", true);
    this.useModifiedTitle = conf.getBoolean("parser.add.custum.title", true);
    this.title_min_length=Integer.parseInt(conf.get("title.min.length","20"));
    this.title_max_length=Integer.parseInt(conf.get("title.max.length","110"));
    this.modifiedTitle = new ModefiedTitle(conf);
  }

  public Configuration getConf() {
    return this.conf;
  }

  @Override
  public Collection<WebPage.Field> getFields() {
    return FIELDS;
  }

  public static void main(String[] args) throws Exception {
    // LOG.setLevel(Level.FINE);
    String name = args[0];
    String url = "file:" + name;
    File file = new File(name);
    byte[] bytes = new byte[(int) file.length()];
    DataInputStream in = new DataInputStream(new FileInputStream(file));
    in.readFully(bytes);
    Configuration conf = NutchConfiguration.create();
    HtmlParser parser = new HtmlParser();
    parser.setConf(conf);
    WebPage page = WebPage.newBuilder().build();
    page.setBaseUrl(new Utf8(url));
    page.setContent(ByteBuffer.wrap(bytes));
    page.setContentType(new Utf8("text/html"));
    Parse parse = parser.getParse(url, page);
    System.out.println("title: " + parse.getTitle());
    System.out.println("text: " + parse.getText());
    System.out.println("outlinks: " + Arrays.toString(parse.getOutlinks()));

  }

}
