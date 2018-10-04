package uk.ac.ebi.ddi.downloas.logs;



import com.google.common.collect.HashMultiset;
import com.google.common.collect.Multiset;
import org.apache.http.client.config.RequestConfig;
import org.apache.log4j.Logger;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchScrollRequest;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.builder.SearchSourceBuilder;

import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.elasticsearch.client.RestHighLevelClient;

import java.io.IOException;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * @author Robert Petryszak rpetry
 *         This class encapsulates the client functionality for accessing ElasticSearch behind Kibana,
 *         where both ftp and Aspera data download logs are stored.
 */
public class ElasticSearchWsClient {

    private static final org.apache.log4j.Logger log = Logger.getLogger(ElasticSearchWsClient.class);

    private RestHighLevelClient restHighLevelClient;
    private ElasticSearchWsConfigProd config;

    // Hashmap for storing results aggregated by period (yyyy/mm)
    private static final Map<ElasticSearchWsConfigProd.DB, Map<String, Map<String, Multiset<String>>>> dbToAccessionToPeriodToFileName =
            new HashMap<ElasticSearchWsConfigProd.DB, Map<String, Map<String, Multiset<String>>>>() {
                {
                    // Initialise all sub-maps
                    for (ElasticSearchWsConfigProd.DB db : ElasticSearchWsConfigProd.DB.values()) {
                        put(db, new HashMap<>());
                    }
                }
            };

    /**
     * Constructor that instantiates RestHighLevelClient object using constants in config
     *
     * @param config
     */
    public ElasticSearchWsClient(ElasticSearchWsConfigProd config) {
        this.config = config;
        final CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
        credentialsProvider.setCredentials(AuthScope.ANY,
                new UsernamePasswordCredentials(ElasticSearchWsConfigProd.USERNAME, ElasticSearchWsConfigProd.PASSWORD));
        RestClientBuilder builder = RestClient.builder(
                new HttpHost(ElasticSearchWsConfigProd.HOST, ElasticSearchWsConfigProd.PORT))
                .setHttpClientConfigCallback(new RestClientBuilder.HttpClientConfigCallback() {
                    public HttpAsyncClientBuilder customizeHttpClient(HttpAsyncClientBuilder httpClientBuilder) {
                        return httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider);
                    }
                }).setRequestConfigCallback(new RestClientBuilder.RequestConfigCallback() {
                    public RequestConfig.Builder customizeRequestConfig(RequestConfig.Builder requestConfigBuilder) {
                        return requestConfigBuilder.setConnectTimeout(ElasticSearchWsConfigProd.CONNECT_TIMEOUT)
                                .setSocketTimeout(ElasticSearchWsConfigProd.SOCKET_TIMEOUT);
                    }
                }).setMaxRetryTimeoutMillis(ElasticSearchWsConfigProd.MAX_RETRY_TIMEOUT);
        this.restHighLevelClient = new RestHighLevelClient(builder);
    }

    /**
     * @param db
     * @param accession
     * @param yearLocalDate
     * @return For a given database, dataset accession and a year (represented by yearLocalDate),
     * return a Map between each Period (yyyy/mm) and a Multiset of file names/download counts
     */
    public Map<String, Multiset<String>> getDataDownloads(ElasticSearchWsConfigProd.DB db, String accession, LocalDate yearLocalDate) {
        Map<String, Multiset<String>> periodToFileNames = null;
        retrieveAllDataFromElasticSearch(null, null, null, yearLocalDate);
        if (dbToAccessionToPeriodToFileName.containsKey(db)) {
            Map<String, Map<String, Multiset<String>>> accessionToPeriodToFileName = dbToAccessionToPeriodToFileName.get(db);
            if (accessionToPeriodToFileName.containsKey(accession)) {
                periodToFileNames = dbToAccessionToPeriodToFileName.get(db).get(accession);
            } else {
                log.warn("No accession: '" + accession + "' could be found in the data retrieved for db: '" + db.toString() + "'from ElasticSearch");
            }
        } else {
            log.warn("No db: '" + db.toString() + "' could be found in the data retrieved from ElasticSearch");
        }
        return periodToFileNames;
    }

    /**
     * @return False if for at least on DB no data downloads are present; otherwise return True
     */
    private boolean resultsReady() {
        boolean resultsReady = true;
        for (ElasticSearchWsConfigProd.DB db : dbToAccessionToPeriodToFileName.keySet()) {
            resultsReady = dbToAccessionToPeriodToFileName.get(db).isEmpty();
            if (!resultsReady)
                break;
        }
        return resultsReady;
    }

    /**
     * Function to retrieve all relevant data download entries for the current year from ftp- and Aspera-specific ElasticSearch indexes,
     * and aggregate them in the static dbToAccessionToPeriodToFileName data structure
     *
     * @param batchSize          If not null, size of each batch to be retrieved from ElasticSearch
     * @param reportingFrequency If not null, the total so far of the records retrieved from ElasticSearch is output every reportingFrequency records
     * @param maxHits            If not null, the maximum number of records to be retrieved (used for testing)
     * @param yearLocalDate      Date representing the year for which the data should be retrieved from ElasticSearch; cannot be null
     */
    private void retrieveAllDataFromElasticSearch(Integer batchSize, Integer reportingFrequency, Integer maxHits, LocalDate yearLocalDate) {
        if (resultsReady()) {
            if (batchSize == null) {
                batchSize = ElasticSearchWsConfigProd.DEFAULT_QUERY_BATCH_SIZE;
            }
            if (reportingFrequency == null) {
                reportingFrequency = ElasticSearchWsConfigProd.DEFAULT_PROGRESS_REPORTING_FREQ;
            }

            // Retrieve year from queryYear
            int year = yearLocalDate.getYear();

            for (ElasticSearchWsConfigProd.Protocol protocol : ElasticSearchWsConfigProd.Protocol.values()) {
                log.info("Starting on protocol: " + protocol.toString());
                String protocolStr = protocol.toString();
                // C.f. https://www.elastic.co/guide/en/elasticsearch/client/java-rest/master/java-rest-high-search-scroll.html
                // Initialise the search scroll context
                SearchRequest searchRequest = new SearchRequest(protocolStr + "logs-*");
                SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
                searchSourceBuilder.query(QueryBuilders.termQuery("year", Integer.toString(year)));
                searchSourceBuilder.size(batchSize);
                searchRequest.source(searchSourceBuilder);
                searchRequest.scroll(TimeValue.timeValueMinutes(ElasticSearchWsConfigProd.SCROLL_VALID_PERIOD));
                try {
                    SearchResponse searchResponse = restHighLevelClient.search(searchRequest, RequestOptions.DEFAULT);
                    String scrollId = searchResponse.getScrollId();
                    SearchHit[] searchHits = searchResponse.getHits().getHits();
                    getValuesFromHits(searchHits, protocol);

                    // Retrieve all the relevant documents
                    int searchHitsCount = 0;
                    while (searchHits != null && searchHits.length > 0 && (maxHits == null || searchHitsCount < maxHits)) {
                        SearchScrollRequest scrollRequest = new SearchScrollRequest(scrollId);
                        scrollRequest.scroll(TimeValue.timeValueSeconds(ElasticSearchWsConfigProd.SCROLL_VALID_PERIOD));
                        SearchResponse searchScrollResponse = restHighLevelClient.scroll(scrollRequest, RequestOptions.DEFAULT);
                        scrollId = searchScrollResponse.getScrollId();
                        searchHits = searchScrollResponse.getHits().getHits();
                        getValuesFromHits(searchHits, protocol);
                        searchHitsCount += batchSize;
                        if ((searchHitsCount % reportingFrequency) == 0) {
                            log.info(searchHitsCount + "");
                        }
                    }
                    log.info("Done retrieving " + protocolStr + " download data");
                } catch (IOException ioe) {
                    log.error("Exception in retrieving data from ElasticSearch via RestHighLevelClient" + ioe.getMessage());
                }
            }
        }
    }

    /**
     * @param source
     * @return Date String in format: yyyy/mm retrieved from source
     */
    private static String getYearMonth(String source) {
        Matcher matcher = Pattern.compile(ElasticSearchWsConfigProd.YEAR_MONTH_REGEX).matcher(source);
        boolean b = matcher.find();
        String dateStr = null;
        if (b) {
            dateStr = matcher.group(0);
        } else {
            log.error("Failed to retrieve date string from: " + source);
        }
        return dateStr;
    }

    /**
     * @param db
     * @param filePath
     * @param accessionRegex
     * @return A tuple first accession and then the name of the file being downloaded
     */
    private static Tuple<String, String> getAccessionAndFileName(ElasticSearchWsConfigProd.DB db, String filePath, String accessionRegex) {
        // c.f. [/\\.] below for retrieval of e.g. /GCA_002757455 from /GCA_002757455.1_
        Matcher matcher = Pattern.compile("/" + accessionRegex + "[/\\.]").matcher(filePath);
        boolean b = matcher.find();
        // Retrieve file name
        String[] arr = filePath.split("/");
        String fileName = arr[arr.length - 1];
        // Retrieve accession
        String accession = null;
        if (b) {
            accession = matcher.group(0).replaceAll("^/|[_/]+$", "");
        } else {
            log.error("ERROR: Failed to retrieve accession from: " + filePath + " - using accession regex: " + accessionRegex);
        }
        return new Tuple(accession, fileName);
    }

    /**
     * Add argument values to the aggregated results in dbToAccessionToDateToFileName
     *
     * @param db
     * @param accession
     * @param period
     * @param fileName
     */
    private static void addToResults(ElasticSearchWsConfigProd.DB db, String accession, final String period, final String fileName) {
        if (!dbToAccessionToPeriodToFileName.get(db).containsKey(accession)) {
            Map<String, Multiset<String>> dateToFileNames = new HashMap<String, Multiset<String>>();
            // N.B. We use Multiset to maintain counts per individual download file
            dateToFileNames.put(period, HashMultiset.<String>create());
            dateToFileNames.get(period).add(fileName);
            dbToAccessionToPeriodToFileName.get(db).put(accession, dateToFileNames);
        } else {
            if (!dbToAccessionToPeriodToFileName.get(db).get(accession).containsKey(period)) {
                dbToAccessionToPeriodToFileName.get(db).get(accession).put(period, HashMultiset.<String>create());
            }
            dbToAccessionToPeriodToFileName.get(db).get(accession).get(period).add(fileName);
        }
    }

    /**
     * Auxiliary function used for testing that ES retrieval works (for a smaller subset of hits)
     * @param batchSize
     * @param reportingFrequency
     * @param maxHits
     * @param yearLocalDate
     * @return
     */
    public Map<ElasticSearchWsConfigProd.DB, Map<String, Map<String, Multiset<String>>>> getResults(Integer batchSize, Integer reportingFrequency, Integer maxHits, LocalDate yearLocalDate) {
        retrieveAllDataFromElasticSearch(batchSize, reportingFrequency, maxHits, yearLocalDate);
        return dbToAccessionToPeriodToFileName;
    }

    /**
     * Retrieves the required fields from each element in searchHits, retrieved for a given protocol
     *
     * @param searchHits
     * @param protocol
     */
    private static void getValuesFromHits(SearchHit[] searchHits, ElasticSearchWsConfigProd.Protocol protocol) {
        for (SearchHit hit : searchHits) {
            Map k2v = hit.getSourceAsMap();
            String source = k2v.get("source").toString();
            // Anonymised IP address: String uhost = k2v.get("uhost").toString();
            String filePath = k2v.get("file_name").toString();
            long fileSize = Long.parseLong(k2v.get("file_size").toString());
            // Ignore files with 0 download size
            if (fileSize == 0)
                continue;
            for (ElasticSearchWsConfigProd.DB db : ElasticSearchWsConfigProd.DB.values()) {
                Map<ElasticSearchWsConfigProd.RegexType, String> typeToRegex = ElasticSearchWsConfigProd.protocol2DB2Regex.get(protocol).get(db);
                if (typeToRegex.keySet().isEmpty())
                    continue;
                String resource = null;
                if (Pattern.compile(typeToRegex.get(ElasticSearchWsConfigProd.RegexType.positive)).matcher(filePath).find()) {
                    if (typeToRegex.get(ElasticSearchWsConfigProd.RegexType.negative) != null) {
                        if (!Pattern.compile(typeToRegex.get(ElasticSearchWsConfigProd.RegexType.negative)).matcher(filePath).find()) {
                            resource = db.toString();
                        }
                    } else {
                        resource = db.toString();
                    }
                }
                if (resource != null) {
                    Tuple<String, String> accessionFileName = getAccessionAndFileName(db, filePath, typeToRegex.get(ElasticSearchWsConfigProd.RegexType.accession));
                    String accession = accessionFileName.v1();
                    String fileName = accessionFileName.v2();
                    if (accession != null) {
                        String yearMonth = getYearMonth(source);
                        if (yearMonth != null) {
                            addToResults(db, accession, yearMonth, fileName);
                            break;
                        }
                    }
                }
            }
        }
    }
}