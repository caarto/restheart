/*
 * Copyright SoftInstigate srl. All Rights Reserved.
 *
 *
 * The copyright to the computer program(s) herein is the property of
 * SoftInstigate srl, Italy. The program(s) may be used and/or copied only
 * with the written permission of SoftInstigate srl or in accordance with the
 * terms and conditions stipulated in the agreement/contract under which the
 * program(s) have been supplied. This copyright notice must not be removed.
 */
package com.softinstigate.restheart.db;

import com.mongodb.BasicDBObject;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBCursor;
import com.mongodb.DBObject;
import com.mongodb.MongoClient;
import com.softinstigate.restheart.utils.HttpStatus;
import com.softinstigate.restheart.utils.RequestHelper;
import com.softinstigate.restheart.utils.ResponseHelper;
import io.undertow.server.HttpServerExchange;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author uji
 */
public class CollectionDAO
{
    private static final MongoClient client = MongoDBClientSingleton.getInstance().getClient();

    private static final Logger logger = LoggerFactory.getLogger(CollectionDAO.class);

    private static final BasicDBObject METADATA_QUERY = new BasicDBObject("_id", "@metadata");
    private static final BasicDBObject DATA_QUERY = new BasicDBObject("_id", new BasicDBObject("$ne", "@metadata"));
    private static final BasicDBObject ALL_FIELDS_BUT_ID = new BasicDBObject("_id", "0");
    
    private static final BasicDBObject fieldsToReturn;
    
    static
    {
        fieldsToReturn = new BasicDBObject();
        fieldsToReturn.put("_id", 1);
        fieldsToReturn.put("@created_on", 1);
    }

    public static boolean checkCollectionExists(HttpServerExchange exchange, String dbName, String collectionName)
    {
        if (!doesCollectionExist(dbName, collectionName))
        {
            ResponseHelper.endExchange(exchange, HttpStatus.SC_NOT_FOUND);
            return false;
        }

        return true;
    }

    public static boolean doesCollectionExist(String dbName, String collectionName)
    {
        if (dbName == null || dbName.isEmpty() || dbName.contains(" "))
        {
            return false;
        }

        return client.getDB(dbName).collectionExists(collectionName);
    }

    public static DBCollection getCollection(String dbName, String collName)
    {
        return client.getDB(dbName).getCollection(collName);
    }

    public static boolean isCollectionEmpty(DBCollection coll)
    {
        return coll.count(DATA_QUERY) == 0;
    }

    public static void dropCollection(DBCollection coll)
    {
        coll.drop();
    }

    public static long getCollectionSize(DBCollection coll)
    {
        return coll.count() - 1; // -1 for the metadata document
    }

    public static List<Map<String, Object>> getCollectionData(DBCollection coll, int page, int pagesize, Deque<String> sortBy, Deque<String> filterBy, Deque<String> filter)
    {
        DAOUtils.getDataFromRow(coll.findOne(METADATA_QUERY, ALL_FIELDS_BUT_ID), "_id");

        // apply sort_by
        DBObject sort = new BasicDBObject();

        if (sortBy == null || sortBy.isEmpty())
        {
            sort.put("_id", 1);
        }
        else
        {
            sortBy.stream().forEach((sf) ->
            {
                if (sf.startsWith("-"))
                {
                    sort.put(sf.substring(1), -1);
                }
                else if (sf.startsWith("+"))
                {
                    sort.put(sf.substring(1), -1);
                }
                else
                {
                    sort.put(sf, 1);
                }
            });
        }

        // apply filter_by and filter
        logger.debug("filter not yet implemented");

        List<Map<String, Object>> data = DAOUtils.getDataFromRows(getDataFromCursor(coll.find(DATA_QUERY).sort(sort).limit(pagesize).skip(pagesize * (page - 1))));

        data.forEach(row ->
        {
            Object etag = row.get("@etag");

            if (etag != null && ObjectId.isValid("" + etag))
            {
                ObjectId _etag = new ObjectId("" + etag);

                row.put("@lastupdated_on", Instant.ofEpochSecond(_etag.getTimestamp()).toString());
            }
        }
        );

        return data;
    }

    public static Map<String, Object> getCollectionMetadata(DBCollection coll)
    {
        Map<String, Object> metadata = DAOUtils.getDataFromRow(coll.findOne(METADATA_QUERY), "_id");

        if (metadata == null)
            metadata = new HashMap<>();
        
        Object etag = metadata.get("@etag");

        if (etag != null && ObjectId.isValid("" + etag))
        {
            ObjectId oid = new ObjectId("" + etag);

            metadata.put("@lastupdated_on", Instant.ofEpochSecond(oid.getTimestamp()).toString());
        }

        return metadata;
    }

    /**
     * 
     * 
     * @param dbName
     * @param collName
     * @param content
     * @param patching
     * @return the HttpStatus code to retrun
     */
    public static int upsertCollection(String dbName, String collName, DBObject content, boolean patching)
    {
        DB db = DBDAO.getDB(dbName);
        
        DBCollection coll = db.getCollection(collName);
        
        boolean updating = CollectionDAO.doesCollectionExist(dbName, collName);
        
        if (patching && !updating)
        {
            return HttpStatus.SC_NOT_FOUND;
        }
        
        ObjectId timestamp = new ObjectId();
        Instant now = Instant.ofEpochSecond(timestamp.getTimestamp());

        if (content == null)
        {
            content = new BasicDBObject();
        }

        if (updating)
        {
            content.removeField("@crated_on"); // don't allow to update this field
            content.put("@etag", timestamp);
        }
        else
        {
            content.put("_id", "@metadata");
            content.put("@created_on", now.toString());
            content.put("@etag", timestamp);
        }
        
        if (patching)
        {
            coll.update(METADATA_QUERY, new BasicDBObject("$set", content), false, false);
            return HttpStatus.SC_OK;
        }
        else
        {
            // we use findAndModify to get the @created_on field value from the existing metadata document
            // we need to put this field back using a second update 
            // it is not possible in a single update even using $setOnInsert update operator
            // in this case we need to provide the other data using $set operator and this makes it a partial update (patch semantic) 
            
            DBObject old = coll.findAndModify(METADATA_QUERY, fieldsToReturn, null, false, content, false, true);

            if (old != null)
            {
                Object oldTimestamp = old.get("@created_on");

                if (oldTimestamp == null)
                {
                    oldTimestamp = now.toString();
                    logger.warn("metadata of collection {} had no @created_on field. set to now", coll.getFullName());
                }

                // need to readd the @created_on field 
                BasicDBObject createdContet = new BasicDBObject("@created_on", "" + oldTimestamp);
                createdContet.markAsPartialObject();
                coll.update(METADATA_QUERY, new BasicDBObject("$set", createdContet), true, false);
                
                return HttpStatus.SC_OK;
            }
            else
            {
                // need to readd the @created_on field 
                BasicDBObject createdContet = new BasicDBObject("@created_on", now.toString());
                createdContet.markAsPartialObject();
                coll.update(METADATA_QUERY, new BasicDBObject("$set", createdContet), true, false);

                initDefaultIndexes(coll);
                
                return HttpStatus.SC_CREATED;
            }
        }
    }
    
    public static int deleteCollection(String dbName, String collName, ObjectId requestEtag)
    {
        DBCollection coll = getCollection(dbName, collName);
        
        if (!isCollectionEmpty(coll))
        {
            return HttpStatus.SC_NOT_ACCEPTABLE;
        }
        
        DBObject metadata = coll.findAndModify(METADATA_QUERY, null, null, true, null, false, false);

        if (metadata != null)
        {
            // check the old etag (in case restore the old document version)
            return optimisticCheckEtag(coll, METADATA_QUERY, metadata, requestEtag);
        }
        else
        {
            coll.drop();
        }
        
        return HttpStatus.SC_GONE;
    }

    private static int optimisticCheckEtag(DBCollection coll, DBObject documentIdQuery, DBObject oldDocument, ObjectId requestEtag)
    {
        Object oldEtag = RequestHelper.getEtagAsObjectId(oldDocument.get("@etag"));

        if (oldEtag == null) // well we don't had an etag there so fine
        {
            return HttpStatus.SC_OK;
        }
        else
        {
            if (oldEtag.equals(requestEtag))
            {
                coll.drop();
                return HttpStatus.SC_GONE; // ok they match
            }
            else
            {
                // oopps, we need to restore old document
                // they call it optimistic lock strategy
                coll.save(oldDocument);
                return HttpStatus.SC_PRECONDITION_FAILED;
            }
        }
    }
    

    public static ArrayList<DBObject> getDataFromCursor(DBCursor cursor)
    {
        return new ArrayList<>(cursor.toArray());
    }
    
    private static void initDefaultIndexes(DBCollection coll)
    {
        coll.createIndex(new BasicDBObject("_id", 1).append("@etag", 1), new BasicDBObject("name", "@_id_etag_idx"));
        coll.createIndex(new BasicDBObject("@etag", 1), new BasicDBObject("name", "@etag_idx"));
        coll.createIndex(new BasicDBObject("@created_on", 1), new BasicDBObject("name", "@created_on_idx"));
    }
}