package com.carleton.paulhayman.RSWebService.models;

import com.carleton.paulhayman.RSWebService.comm.SpaceEntry;
import com.carleton.paulhayman.RSWebService.dao.MongoDbImpl;

public class WriteTransaction extends Transaction {

	public WriteTransaction(SpaceEntry tupleEntry, int transId) {
		super(tupleEntry, transId);
		// TODO Auto-generated constructor stub
	}

	@Override
	public boolean perform() {
	
		MongoDbImpl.getInstance().writeTupleToDB(this);
		return false; //return false to notify listener queue
	}
}
