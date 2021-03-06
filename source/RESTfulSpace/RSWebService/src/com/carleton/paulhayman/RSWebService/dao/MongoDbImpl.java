package com.carleton.paulhayman.RSWebService.dao;

import java.lang.reflect.Type;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;

import org.bson.types.ObjectId;

import com.carleton.paulhayman.RSWebService.comm.Client;
import com.carleton.paulhayman.RSWebService.comm.SpaceEntry;
import com.carleton.paulhayman.RSWebService.models.Transaction;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.mongodb.BasicDBObject;
import com.mongodb.MongoClient;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBObject;
import com.mongodb.util.JSON;

public class MongoDbImpl {

	private static String HOST = "localhost";
	private static int PORT = 27017;
	private static MongoDbImpl instance;
	
	private final String TUPLE_DB = "TupleSpace";
	
	private final String ENTRY_COLLECTION = "TupleEntries";
	private final String CLASS_FIELD = "className";
	private final String SUPER_CLASS_FIELD = "superClassName";
	private final String TTL_FIELD = "tupleExpireAt";
	private final String TUPLE_FIELD = "tupleEntry";
	private final String CLIENT_FK_ID = "clientID";
	
	private final String CLIENT_COLLECTION = "Clients";
	private final String CLIENT_URL_FIELD = "clientURL";
	private final String CLIENT_TTL_FIELD = "clientExpireAt";
	private final String CLIENT_ID_FIELD = "_id";
	
	private MongoClient mongoClient;
	private DB db;
	
	private MongoDbImpl(){
		
		connect(HOST, PORT);
		this.db = mongoClient.getDB(TUPLE_DB);
	}
	
	public void connect(String host, int port) {
		
		try {
			this.mongoClient = new MongoClient(host, port);
		} catch (UnknownHostException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			System.out.println("Can't connect to database");
		}
	}
	
	/*store tuple entry in database as its JSON format and TTL*/
	public void writeTupleToDB(Transaction trans){
		
		SpaceEntry entry = trans.getTupleEntry();
		//create collection by tuple class name, enable TTL for collection
		DBCollection coll = db.getCollection(ENTRY_COLLECTION);
		coll.ensureIndex(new BasicDBObject(TTL_FIELD, 1), 
							new BasicDBObject("expireAfterSeconds", 0));
		coll.ensureIndex(new BasicDBObject(CLIENT_TTL_FIELD, 1),
							new BasicDBObject("expireAfterSeconds",0));
		
		//create entry with JSON serialization, TTL
		DBObject newEntry = new BasicDBObject();
		newEntry.put(TUPLE_FIELD, JSON.parse(entry.getJSONSerializedTuple()));
		newEntry.put(TTL_FIELD, new Date(entry.getExpiryDate()));
		/*fields so tuple is invalid when client terminates*/
		newEntry.put(CLIENT_TTL_FIELD, getClientTTL(trans.getClientID()));
		newEntry.put(CLIENT_FK_ID,  trans.getClientID());
		//add classnames for finding subclass matches
		newEntry.put(CLASS_FIELD,  entry.getClassName());
		//don't use superclass if it is default Object type
		if(!entry.getSuperClassName().equals( Object.class.getCanonicalName())){
			newEntry.put(SUPER_CLASS_FIELD, entry.getSuperClassName());
		}
		//insert into DB
		coll.insert(newEntry);
	}

	/*finds matching tuple where null fields are wild cards*/
	public boolean findTupleMatch(Transaction trans, boolean removeResult){
		
		boolean foundMatch = false;
		SpaceEntry entry = trans.getTupleEntry();
		BasicDBObject query = buildQuery(entry); //create query
		
		/*query for matching tuple that is not expired*/
		DBCollection coll = db.getCollection(ENTRY_COLLECTION); 
		DBObject result = (removeResult) ? coll.findAndRemove(query) : coll.findOne(query); //remove if we are doing a take
		
		//if we found a result set the resulting tuple for the transaction
		if(result != null){
			foundMatch = true;
			trans.setTupleResult(JSON.serialize(result.get(TUPLE_FIELD)), (String) result.get(CLASS_FIELD));
		}
	
		return foundMatch;	
	}
	
	private BasicDBObject buildQuery(SpaceEntry entry){
		
		BasicDBObject query = new BasicDBObject(); //query object to build on
		//get results that haven't expired
		query.put(TTL_FIELD, new BasicDBObject("$gt", new Date(System.currentTimeMillis())));
		query.put(CLIENT_TTL_FIELD, new BasicDBObject("$gt", new Date(System.currentTimeMillis())));
		
		//create query that finds a match if the tuple is the same class or a subclass
		List<BasicDBObject> classCheck = new ArrayList<BasicDBObject>();
		classCheck.add(new BasicDBObject(CLASS_FIELD, entry.getClassName()));
		classCheck.add(new BasicDBObject(SUPER_CLASS_FIELD, entry.getClassName()));
		query.put("$or", classCheck);
		
		Type stringObjectMap = new TypeToken<HashMap<String, Object>>(){}.getType();
		HashMap<String, Object> tupleToFind = new Gson().fromJson(entry.getJSONSerializedTuple(), stringObjectMap);
		//iterate through fields of tuple template and add them to our query
		for (Entry<String, Object> tupleField : tupleToFind.entrySet()) {
			
			String fieldKey = TUPLE_FIELD + "." + tupleField.getKey();
			BasicDBObject wildCard =  new BasicDBObject("$exists",true);
			
			if(tupleField.getValue() == null){ 
				query.put(fieldKey , wildCard);  //if null set wildcard value
			}
			else {
				query.put(fieldKey , tupleField.getValue());
			}
		}
		
		return query;
	}
	
	/*when client is initialized it's url is stored and ID returned*/
	public String addClient(Client clientInfo) {
		
		//create client URL and TTL fields
		DBObject newEntry = new BasicDBObject();
		newEntry.put(CLIENT_URL_FIELD, clientInfo.url);
		newEntry.put(CLIENT_TTL_FIELD, new Date(clientInfo.timeout));
		
		DBObject oldEntry = new BasicDBObject(CLIENT_URL_FIELD, clientInfo.url);
		
		DBCollection coll = db.getCollection(CLIENT_COLLECTION);
		coll.ensureIndex(new BasicDBObject(CLIENT_TTL_FIELD, 1), 
							new BasicDBObject("expireAfterSeconds", 0));
		coll.remove(oldEntry); //remove old clients using this url
		coll.insert(newEntry);	//insert new client into DB
		
		//return id generated by mongoDB
		DBObject result = coll.findOne(newEntry);
		return result.get(CLIENT_ID_FIELD).toString();
	}
	
	/*returns url for response, gets url by client ID*/
	public String getClientURL(String clientID) {
		
		String urlResult = null;
		//get client with same id if it is not expired
		DBObject query = new BasicDBObject();
		query.put(CLIENT_ID_FIELD, new ObjectId(clientID));
		query.put(CLIENT_TTL_FIELD, new BasicDBObject("$gt", new Date(System.currentTimeMillis())));
		
		DBObject queryResult = db.getCollection(CLIENT_COLLECTION).findOne(query);
	
		//if client still online, get its url
		if(queryResult != null) {
			urlResult = (String) queryResult.get(CLIENT_URL_FIELD);
		}
		
		return urlResult;
	}
	
	public Date getClientTTL(String clientID){
		
		Date clientExpiry = new Date(System.currentTimeMillis());
		
		DBObject query = new BasicDBObject(CLIENT_ID_FIELD, new ObjectId(clientID));
		DBObject client = db.getCollection(CLIENT_COLLECTION).findOne(query);
		if(client != null){
			clientExpiry = (Date) client.get(CLIENT_TTL_FIELD);
		}
		return clientExpiry;
	}

	public boolean renewClient(Client clientInfo) {
		boolean success = false;
		
		/*update client with new TTL*/
		DBObject clientToUpdate = new BasicDBObject(CLIENT_ID_FIELD, new ObjectId(clientInfo.clientID));
		DBObject update= new BasicDBObject("$set", new BasicDBObject(CLIENT_TTL_FIELD, new Date(clientInfo.timeout)));
		DBObject updatedClient = db.getCollection(CLIENT_COLLECTION).findAndModify(clientToUpdate, update);
		
		if(updatedClient != null){
			/*update tuple entries with new ttl for that client*/
			DBObject tuplesToUpdate = new BasicDBObject(CLIENT_FK_ID, clientInfo.clientID);
			db.getCollection(ENTRY_COLLECTION).findAndModify(tuplesToUpdate, update);
			success = true;
		}
		
		return success;
	}
	
	public static MongoDbImpl getInstance(){
		if(instance == null)
			instance = new MongoDbImpl();
		return instance;
	}
	
}
