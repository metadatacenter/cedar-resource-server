package org.metadatacenter.server.dao.mongodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.MongoClient;
import com.mongodb.client.MongoCollection;
import org.bson.Document;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.metadatacenter.server.dao.GenericResourceDao;
import org.metadatacenter.server.model.CedarResource;
import org.metadatacenter.util.FixMongoDirection;
import org.metadatacenter.util.MongoFactory;
import org.metadatacenter.util.json.JsonUtils;

import java.io.IOException;
import java.util.Map;

import static com.mongodb.client.model.Filters.eq;

public class ResourceDaoMongoDB implements GenericResourceDao {

  protected final @NonNull MongoCollection<Document> entityCollection;
  protected final @NonNull JsonUtils jsonUtils;

  public ResourceDaoMongoDB(@NonNull String dbName, @NonNull String collectionName) {
    MongoClient mongoClient = MongoFactory.getClient();
    entityCollection = mongoClient.getDatabase(dbName).getCollection(collectionName);
    jsonUtils = new JsonUtils();
  }

  @Override
  public @NonNull CedarResource create(@NonNull CedarResource resource) throws IOException {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode resourceNode = mapper.valueToTree(resource);
    // Adapts all keys not accepted by MongoDB
    JsonNode fixedElement = jsonUtils.fixMongoDB(resourceNode, FixMongoDirection.WRITE_TO_MONGO);
    Map elementMap = mapper.convertValue(fixedElement, Map.class);
    Document elementDoc = new Document(elementMap);
    entityCollection.insertOne(elementDoc);
    // Returns the document created (all keys adapted for MongoDB are restored)
    JsonNode savedUser = mapper.readTree(elementDoc.toJson());
    JsonNode fixedUser = jsonUtils.fixMongoDB(savedUser, FixMongoDirection.READ_FROM_MONGO);
    return mapper.treeToValue(fixedUser, CedarResource.class);
  }

  @Override
  public CedarResource find(@NonNull String resourceId) throws IOException {
    if ((resourceId == null) || (resourceId.length() == 0)) {
      throw new IllegalArgumentException();
    }
    Document doc = entityCollection.find(eq("resourceId", resourceId)).first();
    if (doc == null) {
      return null;
    }
    ObjectMapper mapper = new ObjectMapper();
    JsonNode readResource = mapper.readTree(doc.toJson());
    JsonNode fixedResource = jsonUtils.fixMongoDB(readResource, FixMongoDirection.READ_FROM_MONGO);
    return mapper.treeToValue(fixedResource, CedarResource.class);
  }

}