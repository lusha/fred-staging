package freenet.node;

import freenet.support.Logger;
import freenet.support.TimeUtil;

/**
 * Represents an insert.
 * @author Matthew Toseland <toad@amphibian.dyndns.org> (0xE43DA450)
 */
public class InsertTag extends UIDTag {
	
	final boolean ssk;
	
	enum START {
		LOCAL,
		REMOTE
	}
	
	START start;
	private Throwable handlerThrew;
	
	InsertTag(boolean ssk, START start) {
		super();
		this.start = start;
		this.ssk = ssk;
	}

	public void handlerThrew(Throwable t) {
		handlerThrew = t;
	}

	@Override
	public void logStillPresent(Long uid) {
		StringBuffer sb = new StringBuffer();
		sb.append("Still present after ").append(TimeUtil.formatTime(age()));
		sb.append(" : ").append(uid).append(" : start=").append(start);
		sb.append(" ssk=").append(ssk);
		sb.append(" thrown=").append(handlerThrew);
		if(handlerThrew != null)
			Logger.error(this, sb.toString(), handlerThrew);
		else
			Logger.error(this, sb.toString());
	}

}
