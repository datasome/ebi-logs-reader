package uk.ac.ebi.ddi.downloas.logs;

import com.google.common.collect.Multiset;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

import java.time.LocalDate;
import java.util.Map;

/**
 * This code is licensed under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * ==Overview==
 *
 * @author ypriverol on 04/10/2018.
 */
public class ElasticSearchWsClientTest {

    ElasticSearchWsClient elasticSearchClient = new ElasticSearchWsClient(new ElasticSearchWsConfigProd(1,"localhost","readme", "readmed"));

    /**
     * This test is really slow should be used only to test locally and it needs more than 12G memory.
     */
    @Test
    public void getDataDownloads() {
        elasticSearchClient.setParallel(true);
        Map<String, Map<String, Multiset<String>>> prideDownloads =
                elasticSearchClient.getDataDownloads(ElasticSearchWsConfigProd.DB.Pride, "PXD000533");
        Assert.assertTrue(prideDownloads.size() > 0);
    }

}