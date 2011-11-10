/* 
 * Copyright(c) 2005 Center for E-Commerce Infrastructure Development, The
 * University of Hong Kong (HKU). All Rights Reserved.
 *
 * This software is licensed under the GNU GENERAL PUBLIC LICENSE Version 2.0 [1]
 * 
 * [1] http://www.gnu.org/licenses/old-licenses/gpl-2.0.txt
 */

package hk.hku.cecid.piazza.commons.util;

import java.sql.Timestamp;

/**
 * 
 * Creation Date: 5/12/2006
 * 
 * @author Twinsen Tsang
 * @version 1.0.0
 * @since	1.0.3
 */
public class StopWatch {

	private long startTime;	
	private long endTime;
	
	private static final Timestamp currentTimestamp = new Timestamp(System.currentTimeMillis());
		
	public static final Timestamp getCurrentTimestamp(){
		currentTimestamp.setTime(System.currentTimeMillis());
		return currentTimestamp;
	}
	
	public void 
	start()
	{
		this.startTime = System.currentTimeMillis();
	}
	
	public void 
	stop()
	{
		this.endTime = System.currentTimeMillis();
	}
	
	public void 
	reset()
	{
		this.startTime = this.endTime = 0;
	}
	
	public long 
	getStartTime()
	{
		return this.startTime;
	}
	
	public long 
	getEndTime()
	{
		return this.endTime;
	}
	
	public long 
	getElapsedTime()
	{
		return this.endTime - this.startTime;
	}
	
	public double 
	getElapsedTimeInSecond()
	{
		return  (((double)this.endTime - this.startTime) / 1000);
	}
}
