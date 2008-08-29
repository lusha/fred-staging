package freenet.client.async;

import java.util.ArrayList;

import com.db4o.ObjectContainer;

import freenet.node.SendableRequest;

/**
 * Just as with SectoredRandomGrabArray, activation is a big deal, and we can
 * safely assume that == <=> equals(). So we use a vector, and hope it doesn't
 * get too big (it won't in the near future). Any structure that might conceivably
 * call equals() is doomed, because either it requires activation (extra disk 
 * seek), or it will cause NPEs or messy code to avoid them. One option if size
 * becomes a problem is to have individual objects in the database for each
 * SendableRequest; this might involve many disk seeks, so is bad.
 * @author Matthew Toseland <toad@amphibian.dyndns.org> (0xE43DA450)
 */
public class PersistentSendableRequestSet implements SendableRequestSet {

	private final ArrayList<SendableRequest> list;
	
	PersistentSendableRequestSet() {
		list = new ArrayList();
	}
	
	public synchronized boolean addRequest(SendableRequest req, ObjectContainer container) {
		container.activate(list, 1);
		int idx = find(req);
		if(idx == -1) {
			list.add(req);
			container.set(list);
			return true;
		} else return false;
	}

	private synchronized int find(SendableRequest req) {
		for(int i=0;i<list.size();i++)
			if(list.get(i) == req) return i;
		return -1;
	}

	public synchronized SendableRequest[] listRequests(ObjectContainer container) {
		container.activate(list, 1);
		return (SendableRequest[]) list.toArray(new SendableRequest[list.size()]);
	}

	public synchronized boolean removeRequest(SendableRequest req, ObjectContainer container) {
		container.activate(list, 1);
		int idx = find(req);
		if(idx == -1) return false;
		list.remove(idx);
		container.set(list);
		return true;
	}

}