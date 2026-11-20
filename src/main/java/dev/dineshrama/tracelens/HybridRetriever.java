package dev.dineshrama.tracelens;

import java.sql.Array;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class HybridRetriever {
  private final Workspace workspace;
  private final OllamaClient model;
  private final JdbcTemplate db;
  private final TraceProperties config;
  public HybridRetriever(Workspace workspace, OllamaClient model, JdbcTemplate db, TraceProperties config) {
    this.workspace=workspace; this.model=model; this.db=db; this.config=config;
  }
  public synchronized int ensureIndex() {
    var docs=workspace.runbooks();
    if (docs.size()>1000) throw new Problem(422,"CORPUS_TOO_LARGE","This version supports a bounded corpus of at most 1000 runbook chunks.");
    var missing=new ArrayList<Map<String,Object>>();
    for (var doc:docs) {
      Integer count=db.queryForObject("SELECT count(*) FROM embeddings WHERE document_id=? AND model=? AND content_hash=?",Integer.class,doc.get("id"),config.embeddingModel(),doc.get("content_hash"));
      if (count==null || count==0) missing.add(doc);
    }
    for(int start=0; start<missing.size(); start+=16) {
      var batch=missing.subList(start,Math.min(start+16,missing.size()));
      var vectors=model.embed(batch.stream().map(d->d.get("title")+". "+d.get("body")).toList());
      for(int i=0;i<batch.size();i++) {
        var doc=batch.get(i); Double[] vector=Arrays.stream(vectors.get(i)).boxed().toArray(Double[]::new);
        db.execute((java.sql.Connection c)-> {
          var ps=c.prepareStatement("INSERT INTO embeddings(document_id,model,content_hash,vector) VALUES(?,?,?,?) ON CONFLICT(document_id,model) DO UPDATE SET content_hash=EXCLUDED.content_hash,vector=EXCLUDED.vector");
          ps.setString(1,doc.get("id").toString()); ps.setString(2,config.embeddingModel()); ps.setString(3,doc.get("content_hash").toString()); ps.setArray(4,c.createArrayOf("float8",vector));
          return ps;
        },(org.springframework.jdbc.core.PreparedStatementCallback<Integer>)ps->ps.executeUpdate());
      }
    }
    return missing.size();
  }

  public Map<String,Object> search(String query, int topK) {
    long start=System.nanoTime();
    ensureIndex();
    String bounded=query.substring(0,Math.min(240,query.length()));
    var lexical=workspace.lexical(bounded);
    double[] embedding=model.embed(List.of(bounded)).getFirst();
    List<Map<String,Object>> semantic=db.query("SELECT r.id,r.title,r.body,e.vector FROM runbooks r JOIN embeddings e ON e.document_id=r.id AND e.content_hash=r.content_hash WHERE e.model=?",(rs,n)-> {
      Array sqlArray=rs.getArray("vector"); Object[] raw=(Object[])sqlArray.getArray();
      double[] vector=new double[raw.length]; for(int i=0;i<raw.length;i++)vector[i]=((Number)raw[i]).doubleValue(); sqlArray.free();
      var row=new LinkedHashMap<String,Object>();row.put("id",rs.getString("id"));row.put("title",rs.getString("title"));row.put("body",rs.getString("body"));row.put("similarity",cosine(embedding,vector));return row;
    },config.embeddingModel());
    semantic.sort(Comparator.<Map<String,Object>>comparingDouble(d->-((Number)d.get("similarity")).doubleValue()).thenComparing(d->d.get("id").toString()));
    var ranks=new LinkedHashMap<String,Map<String,Object>>();
    for(int i=0;i<Math.min(6,semantic.size());i++) {
      var row=new LinkedHashMap<>(semantic.get(i));row.put("semanticRank",i+1);row.put("lexicalRank",null);row.put("score",1.0/(60+i+1));ranks.put(row.get("id").toString(),row);
    }
    for(int i=0;i<lexical.size();i++) {
      var d=lexical.get(i);var row=ranks.computeIfAbsent(d.get("id").toString(),ignored->{var x=new LinkedHashMap<>(d);x.put("semanticRank",null);x.put("similarity",null);x.put("score",0.0);return x;});
      row.put("lexicalRank",i+1);row.put("score",((Number)row.get("score")).doubleValue()+1.0/(60+i+1));
    }
    var hits=new ArrayList<>(ranks.values());hits.sort(Comparator.<Map<String,Object>>comparingDouble(d->-((Number)d.get("score")).doubleValue()).thenComparing(d->d.get("id").toString()));
    return Map.of("query",bounded,"method","reciprocal-rank-fusion","rrfConstant",60,"embeddingModel",config.embeddingModel(),"milliseconds",(System.nanoTime()-start)/1_000_000,
        "hits",hits.subList(0,Math.min(Math.clamp(topK,1,6),hits.size())),"lexical",lexical.stream().map(d->d.get("id")).toList(),"semantic",semantic.stream().limit(6).map(d->d.get("id")).toList());
  }
  public static double cosine(double[] a, double[] b) {
    if(a.length!=b.length)throw new Problem(502,"EMBEDDING_DIMENSION_MISMATCH","Reindex runbooks with the configured embedding model.");
    double dot=0,aa=0,bb=0;
    for(int i=0;i<a.length;i++){dot+=a[i]*b[i];aa+=a[i]*a[i];bb+=b[i]*b[i];}
    return aa==0||bb==0?0:dot/Math.sqrt(aa*bb);
  }
}
