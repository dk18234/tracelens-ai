#!/usr/bin/env python3
"""Create synthetic traffic on a disposable local lab, then verify read-only import."""
import hashlib
import hmac
import json
import os
from pathlib import Path
import time
import urllib.request
import uuid

ROOT=Path(__file__).resolve().parents[1]

def main():
    lab=os.getenv('WEBHOOK_BASE_URL','http://localhost:8080').rstrip('/')
    trace=os.getenv('LAB_URL','http://localhost:8081').rstrip('/')
    upstream_token=os.environ['WEBHOOK_ADMIN_TOKEN']
    secret=os.environ['WEBHOOK_SECRET']
    token=os.getenv('ADMIN_TOKEN','local-tracelens-token-change-before-hosting')
    def api(base,path,auth,body=None,extra=None):
        data=None if body is None else (body if isinstance(body,bytes) else json.dumps(body).encode())
        headers={'Content-Type':'application/json','X-Admin-Token':auth}|(extra or {})
        with urllib.request.urlopen(urllib.request.Request(base+path,data=data,headers=headers),timeout=30) as r:return json.load(r)
    event_id='evt_bridge_'+uuid.uuid4().hex[:12]
    payload=json.dumps({'id':event_id,'accountId':'acct_demo','credits':10},separators=(',',':')).encode()
    timestamp=str(int(time.time()))
    signature='v1='+hmac.new(secret.encode(),timestamp.encode()+b'.'+payload,hashlib.sha256).hexdigest()
    api(lab,'/webhooks/credits',upstream_token,payload,{'X-Webhook-Timestamp':timestamp,'X-Webhook-Signature':signature})
    for _ in range(20):
        api(lab,'/api/worker/tick',upstream_token,{})
        before=api(lab,'/api/events/'+event_id,upstream_token)
        if before['event']['status']=='SUCCEEDED':break
        time.sleep(.2)
    assert before['event']['status']=='SUCCEEDED',before
    assert len(before['ledger'])==1,before
    first=api(trace,'/api/import/webhook',token,{'eventId':event_id})
    first_capture=api(trace,'/api/incidents/'+first['incidentId'],token)
    second=api(trace,'/api/import/webhook',token,{'eventId':event_id})
    second_capture=api(trace,'/api/incidents/'+second['incidentId'],token)
    after=api(lab,'/api/events/'+event_id,upstream_token)
    assert first_capture['snapshot']['ledger']['known'] is True
    assert len(first_capture['snapshot']['ledger']['entries'])==1
    assert second_capture['revision']==first_capture['revision']+1
    assert after['event']==before['event']
    assert after['ledger']==before['ledger']
    output={'passed':True,'upstreamEventId':event_id,'upstreamStatus':after['event']['status'],
        'ledgerEntries':len(after['ledger']),'firstRevision':first_capture['revision'],
        'secondRevision':second_capture['revision'],'upstreamEventUnchanged':True,
        'upstreamLedgerUnchanged':True,'importMethod':first['upstreamMethod'],
        'note':'Synthetic traffic was explicitly created by the test utility. The TraceLens adapter made only GET requests.'}
    dest=ROOT/'docs/evidence/webhook-bridge-smoke.json';dest.parent.mkdir(exist_ok=True,parents=True)
    dest.write_text(json.dumps(output,indent=2)+'\n');print(json.dumps(output),flush=True)

if __name__=='__main__':main()
