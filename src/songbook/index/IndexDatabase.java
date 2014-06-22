package songbook.index;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.RAMDirectory;
import org.apache.lucene.util.Version;
import org.vertx.java.core.http.HttpServerRequest;
import org.vertx.java.core.http.HttpServerResponse;
import org.xml.sax.SAXException;
import songbook.server.Templates;

import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Created by laurent on 08/05/2014.
 */
public class IndexDatabase {

    public final static String SONG_EXTENSION = ".html";

    public final static IndexEntityType[] INDEX_ENTITY_TYPES = {
            new IndexEntityType("lyrics", "song", false),
            new IndexEntityType("title", "song-title", true),
            new IndexEntityType("author", "song-author", true),
            new IndexEntityType("album", "song-album", true),
    };

    private StandardAnalyzer analyzer;

    private Directory index;

    public IndexDatabase(Path indexFolder) throws IOException {
        // 0. Specify the analyzer for tokenizing text.
        //    The same analyzer should be used for indexing and searching
        analyzer = new StandardAnalyzer(Version.LUCENE_48);

        // 1. create the index
        index = new RAMDirectory();

    }

    public void analyzeSongs(Path songsFolder) throws IOException {
        IndexWriterConfig config = new IndexWriterConfig(Version.LUCENE_48, analyzer);
        IndexWriter w = new IndexWriter(index, config);
        HtmlIndexer songIndexer = new HtmlIndexer(INDEX_ENTITY_TYPES);
        Files.walk(songsFolder).forEach(filePath -> {
            if (Files.isRegularFile(filePath) && filePath.toString().endsWith(".html")) {
                try {
                    songIndexer.indexSong(w, filePath);
                } catch (IOException e) {
                    e.printStackTrace();
                } catch (ParserConfigurationException e) {
                    e.printStackTrace();
                } catch (SAXException e) {
                    e.printStackTrace();
                }
            }
        });
        w.close();
    }


    public void search(String querystr, HttpServerRequest request) throws ParseException, IOException {
        HttpServerResponse response = request.response();
        response.setChunked(true);


        // 3. search
        int hitsPerPage = 50;
        IndexReader reader = DirectoryReader.open(index);
        IndexSearcher searcher = new IndexSearcher(reader);
        TopScoreDocCollector collector = TopScoreDocCollector.create(hitsPerPage, true);
        Query q;
        if (querystr == null || querystr.isEmpty()) {
            q = new MatchAllDocsQuery();
        } else {
            // the "lyrics" arg specifies the default field to use
            // when no field is explicitly specified in the query.
            q = new QueryParser(Version.LUCENE_48, "lyrics", analyzer).parse(querystr);
        }

        searcher.search(q, collector);

        ScoreDoc[] hits = collector.topDocs().scoreDocs;
        // 4. display results
        response.write(Templates.startResult());
        for (int i = 0; i < hits.length; ++i) {
            int docId = hits[i].doc;
            Document d = searcher.doc(docId);
            response.write(Templates.showDocument(d));
        }
        response.write(Templates.endResult());

        // reader can only be closed when there
        // is no need to access the documents any more.
        reader.close();
    }

}
