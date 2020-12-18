-------------- Installation --------------------------------------
1. Download Nutch 2.4 from its online repository
2. First, compile is according to the guidance of Nutch WIKI and crawl some sample pages

For NCL-Crawl module integration, follow these steps:
1. Go to the src/plugin from Nutch Home directory and remove parse-html plugin (or rather move somewhere else)
2. Now copy parse-html given from the corpulyzer
3. Compile Nutch using ant
4. After compilation successfull, register this plugin into Nutch via conf/nutch-site.xml (if not already done). It should be like below:

<property>
        <name>plugin.includes</name>
	<value>parse-(html|tika)|index-(basic|more)|urlnormalizer-(pass|regex|basic)</value>
 </property>
<property>
   <name>tika.extractor</name>
   <value>boilerpipe</value>
</property>


5. For this plugin to work properly, you should have to configure language filter and CLD2 REST service parameters in conf/nutch-site.xml. 
 
<!--  Language Controller -->
<property>
  <name>filter.lang.enable</name>
  <value>true</value>
  <description>Enable/Disable Language filter. If this value is false then min/max size filters will be disabled. default is false</description>
</property>

<property>
  <name>filter.lang.label</name>
  <value>Urdu</value>
  <description>language title for filters. default is Urdu</description>
</property>

<property>
  <name>filter.lang.minSize.enable</name>
  <value>true</value>
  <description>Enable or disable minimum size filter; default is false. For this, language filter should be true</description>
</property>

<property>
  <name>filter.lang.minSize.bytes</name>
  <value>256</value>
  <description>Set minimum bytes for min-size language filter. e</description>
</property>


<!--  CLD2 REST configurations-->
<property>
	<name>cld2.rest.url</name>	
	<value>http://localhost</value>
	<description>If cld2.enable is true then URL of CLD2 API. example http://10.13.2.12 </description>
</property>
<property>
	<name>cld2.rest.port</name>	
	<value>6161</value>
	<description> If cld2.enable is true then port of CLD2 API. example 1122 </description>
</property>

6. Finally, you should have to install CLD2 rest also (guide is given in next section). Update IP and port according to your setting in conf/nutch-site.xml. 


7. The information related to the language for each fetched document is stored in mk column family of Hbase table. There will be three columns for this 
	Complete CLD2 String	-> "mk:cld2"
	Urdu Percentage		-> "mk:usc"
	Urdu Bytes		-> "mk:lb"

------------------------------------------------------
CLD2 rest installation
1. copy cld2-rest of corpulyzer to the machine from where you want to provide service e.g., localhost
2. Navigate to folder cld2-rest
3. Install dependencies
	pip install -r requirements.txt
	or	
	pip3 install -r requirements.txt
	
4. Run the service
	python cld2-service.py
	or
	python3 cld2-service.py
5. Testing
	curl http://127.0.0.1:6161/cld2?text="Welcome to the rest of CLD2 for corpulyzer"
------------------------------------------------------

LRL website Scoring module Integration
1. As this module is not a plugin, you should have to copy the domain directory of corpulyzer in the source of Nutch at src/java/org/apache/nutch
2. Compile the Nutch using ant
3. Append this cycle just after updatedb job in bin/crawl for automation. Or you can use use scripts given in corpulyzer under "nutch-scripts" directory. The execution details are as follows:
	bin/nutch org.apache.nutch.domain.DomainAnalysisJob -crawlId "$CRAWL_ID













