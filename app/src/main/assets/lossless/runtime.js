// SpotiFLAC host API compatibility. Provider source is installed from its verified .sflx package.
var extension = null;
function registerExtension(value) { extension = value; }
function hostCall(op, args) { return JSON.parse(__host(op, JSON.stringify(args || []))); }
var log = { debug:function(){}, info:function(){}, warn:function(){}, error:function(){} };
var console = { log:function(){}, warn:function(){}, error:function(){} };
var storage = { get:function(k){return hostCall('storageGet',[String(k)]);}, set:function(k,v){return hostCall('storageSet',[String(k),v]);}, remove:function(k){return hostCall('storageRemove',[String(k)]);} };
function fetch(url, options) {
    options = options || {};
    var r = hostCall('http',[String(options.method || 'GET').toUpperCase(),String(url),options.body == null ? '' : String(options.body),options.headers || {}]);
    r.text = function(){return this.body;};
    r.json = function(){return JSON.parse(this.body);};
    return r;
}
var http = {
    get:function(url,headers){return fetch(url,{headers:headers});},
    post:function(url,body,headers){return fetch(url,{method:'POST',body:body,headers:headers});},
    request:function(url,options){return fetch(url,options);}
};
var session = {
    signedFetch:function(method,path,body,headers){return hostCall('signed',[String(method),String(path),body == null ? '' : (typeof body === 'string' ? body : JSON.stringify(body)),headers || {}]);},
    status:function(){return hostCall('sessionStatus');},
    completeGrant:function(){return hostCall('sessionStatus');}
};
var file = {
    download:function(url,path,options){return hostCall('download',[String(url),String(path),(options || {}).headers || {}]);},
    downloadSegments:function(segments,path,options){return hostCall('segments',[segments,String(path),(options || {}).headers || {}]);},
    exists:function(path){return hostCall('exists',[String(path)]);},
    delete:function(path){return hostCall('delete',[String(path)]);}
};
var ffmpeg = {};
var gobackend = {getAudioQuality:function(path){return hostCall('quality',[String(path)]);}};
var utils = {
    randomUserAgent:function(){return 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36';},
    appUserAgent:function(){return hostCall('userAgent');},
    appVersion:function(){return '4.9.5';},
    sha256:function(value){return hostCall('sha256',[String(value)]);},
    md5:function(value){return hostCall('md5',[String(value)]);},
    base64Encode:function(value){return hostCall('base64Encode',[String(value)]);},
    base64Decode:function(value){return hostCall('base64Decode',[String(value)]);},
    sleep:function(ms){return hostCall('sleep',[Math.max(0,Number(ms) || 0)]);},
    isDownloadCancelled:function(){return hostCall('cancelled');},
    isRequestCancelled:function(){return hostCall('cancelled');},
    getResolutionRemainingMs:function(){return hostCall('remaining');},
    setDownloadStatus:function(value){return true;}
};
var matching = {
    normalizeString:function(value){return String(value || '').toLowerCase().replace(/[^a-z0-9]+/g,' ').trim();},
    compareDuration:function(a,b,tolerance){return Math.abs(Number(a)-Number(b)) <= (tolerance == null ? 3000 : Number(tolerance));}
};
var atob = utils.base64Decode;
var btoa = utils.base64Encode;
function URL(value, base) {
    var parts = hostCall('url',[String(value),base == null ? '' : String(base)]);
    for (var key in parts) this[key] = parts[key];
    var pairs = parts.parameters;
    this.searchParams = {get:function(key){return Object.prototype.hasOwnProperty.call(pairs,key) ? pairs[key] : null;}};
    this.toString = function(){return this.href;};
}
