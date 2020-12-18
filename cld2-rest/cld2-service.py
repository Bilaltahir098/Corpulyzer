#! /usr/bin/python
# -*- coding: utf-8 -*-
#This script is written to request cld2 via REST API.
# Example
# http://127.0.0.1:6161/cld2?text="Your input text string"
# OUTPUT ( It gives output as we done in CC)
#"585&URDU-99-1155"


from flask import Flask,abort,jsonify,request
from flask_restful import Resource, Api, reqparse
import pycld2 as cld2
from bs4 import BeautifulSoup
import sys
import urllib3, urllib
import re
import string

reload(sys)
sys.setdefaultencoding('utf8')


app = Flask(__name__)
api = Api(app)


class HelloWorld(Resource):
    def parse_url_cc(self, url):
        try:
            response = urllib2.urlopen(url, timeout=30)
        except:
            return "URL NOT Accessible!!!"
        scripts = re.compile(r'<(script).*?</\1>(?s)')
        css = re.compile(r'(<style.*?/style>)|(<.*?>)|( )|((?=<!--)([\s\S]*?-->))')
        txt = response.read()

        # Remove html tags
        txt = re.sub(r"\n+", " ", txt)
        txt = scripts.sub('', txt)
        txt = css.sub('', txt)
        txt = re.sub(r"\s+", " ", txt)
        result = self.get_lang_info(txt.encode('utf8'))
        return result

    def get_lang_info(self, txt):
        try:
            txt = txt.encode('utf8')
            isReliable, textBytesFound, details = cld2.detect(txt)
        except:
            txt = ''.join(x for x in txt if x in string.printable)  # Handle invalid utf-8 chars
            isReliable, textBytesFound, details = cld2.detect(txt)
        outstr = str(textBytesFound)
        out_dict = {"ur" : 0}
        for item in details:  # Iterate 3 languages

            if item[0] != "Unknown":
                outstr += '&' + item[0] + '-' + str(item[2]) + '-' + str(int(item[3]))
            if item[0] == "URDU":
                out_dict["ur"] = item[2]
        out_dict["result"] = outstr
        return out_dict

    def get(self):
        parser = reqparse.RequestParser()
        parser.add_argument('text', type=str)
        parser.add_argument('url', type=str)

        _dict =  dict(parser.parse_args())
        if _dict["text"] is not None:
            value = _dict["text"]
            return self.get_lang_info(value)

        if _dict["url"] is not None:
            value = _dict["url"]
            return self.parse_url_cc(value)

        return None

    def post(self):
        data = request.get_json(force=True)
        predict_request = [data['content']][0]
        predict_request = predict_request.encode("utf8")
        predict_request = urllib.unquote(predict_request)
        out = self.get_lang_info(predict_request)
        print(out["result"], predict_request)
        return jsonify(score=out["result"], ur=out["ur"])


api.add_resource(HelloWorld, '/cld2')
if __name__ == '__main__':
    app.run(debug=False, port=6161, host='0.0.0.0')

