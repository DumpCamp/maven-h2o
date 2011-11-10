/**
 * Provides database and message handler and some utility generators at
 * the high level architecture.  
 */
package hk.hku.cecid.edi.sfrm.handler;

import java.io.File;
import java.io.RandomAccessFile;
import java.io.IOException;
import java.io.InputStream;
import java.nio.MappedByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.FileChannel;
import java.util.Properties;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.sql.Timestamp;
import java.net.MalformedURLException;

import javax.mail.MessagingException;

import hk.hku.cecid.edi.sfrm.spa.SFRMException;
import hk.hku.cecid.edi.sfrm.spa.SFRMLog;
import hk.hku.cecid.edi.sfrm.spa.SFRMLogUtil;
import hk.hku.cecid.edi.sfrm.spa.SFRMProcessor;
import hk.hku.cecid.edi.sfrm.spa.SFRMProperties;

import hk.hku.cecid.edi.sfrm.pkg.SFRMConstant;
import hk.hku.cecid.edi.sfrm.pkg.SFRMMessage;
import hk.hku.cecid.edi.sfrm.pkg.SFRMMessageClassifier;
import hk.hku.cecid.edi.sfrm.pkg.SFRMMessageException;

import hk.hku.cecid.edi.sfrm.dao.SFRMMessageDVO;
import hk.hku.cecid.edi.sfrm.dao.SFRMPartnershipDVO;
import hk.hku.cecid.edi.sfrm.dao.SFRMMessageSegmentDVO;

import hk.hku.cecid.edi.sfrm.handler.SFRMMessageHandler;
import hk.hku.cecid.edi.sfrm.handler.SFRMMessageSegmentHandler;

import hk.hku.cecid.edi.sfrm.com.FoldersPayload;
import hk.hku.cecid.edi.sfrm.com.PayloadsState;
import hk.hku.cecid.edi.sfrm.com.PackagedPayloads;

import hk.hku.cecid.piazza.commons.io.ChecksumException;
import hk.hku.cecid.piazza.commons.dao.DAOException;
import hk.hku.cecid.piazza.commons.module.Component;
import hk.hku.cecid.piazza.commons.module.ActiveMonitor;
import hk.hku.cecid.piazza.commons.module.ActiveThread;
import hk.hku.cecid.piazza.commons.module.ActiveTaskAdaptor;
import hk.hku.cecid.piazza.commons.os.OSManager;
import hk.hku.cecid.piazza.commons.security.KeyStoreManager;
import hk.hku.cecid.piazza.commons.util.StringUtilities;

/**
 * The incoming message handler is the core class for handling
 * all incoming SFRM segment.<br><br>
 * 
 * It also handles:<br> 
 * <ol>
 * 	<li>Allocation of disk space for HANDSHAKING segment.</li>
 * 	<li>Insertion of data content to specified file for PAYLOAD segment.</li>
 * 	<li>RECEIPT Response handling</li>
 * 	<li>RECOVERY when data integrity check fails.<li> 
 * 	<li>Error Definition and handling<li>
 * </ol>  
 * 
 * For details, read {@link #processIncomingMessage(SFRMMessage, Object[])}
 * as the entry point for knowing how this class work.  
 * 
 * Creation Date: 11/10/2006
 * 
 * @author Twinsen Tsang
 * @version 1.0.4 [26/6/2007 - 3/7/2007]
 * @since 	1.0.0
 */
public class IncomingMessageHandler extends Component {
		
	// Singleton Handler.
	private static IncomingMessageHandler imh = new IncomingMessageHandler();
			
	// The active thread pool.
	// TODO: Implement a thread pool with priority and thread.
	private ActiveMonitor monitor = new ActiveMonitor();	
	
	// The barrier for providing ONE THREAD working in the segment level.
	private SFRMDoSHandler segmentDoSHandler = new SFRMDoSHandler();
	
	/**
	 * @return an instnace of IncomingMessageHandler.
	 */
	public static IncomingMessageHandler getInstance(){
		return IncomingMessageHandler.imh;
	}
		
	/**
	 * Invoked for initialization.<br><br>
	 * 
	 * The IMH has serval properties : <br>
	 * <ol>
	 * 	<li>maxActive: The number of parallel threads for handling incoming segments. The default value is 10.
	 * 		[Integer] 
	 *	</li>
	 * </ol>
	 */
	protected void init() throws Exception { 
		super.init();
		Properties p = this.getParameters();
		int maxActive = StringUtilities.parseInt(p.getProperty("maxActive"), 10);
		this.monitor.setMaxThreadCount(maxActive);
	}

	/**
	 * Validate whether the partnership for the incoming message 
	 * is exist and return that partnership as return value.<br><br>
	 * 
	 * [SINGLE-THREADED].
	 * 
	 * @param incomingMessage
	 * 			The incoming SFRM message.
	 * @return
	 * 			A SFRM partnership record.
	 * @since
	 * 			1.0.0 
	 * @throws MalformedURLException
	 * 			throw if the partnership does not found or 
	 * 			any other database error. 
	 */
	public SFRMPartnershipDVO 
	extractPartnership(SFRMMessage incomingMessage) 
		throws MalformedURLException 
	{ 
		SFRMPartnershipDVO partnershipDVO = null;
		try {
			// Extract the partnership from the message and the partnership handler.
			partnershipDVO = (SFRMPartnershipDVO) SFRMProcessor
				.getPartnershipHandler().retreivePartnership(incomingMessage);
			// Check null.
			if (partnershipDVO == null){
				String pID = incomingMessage.getPartnershipId();
				String err = "Missing partnership Information with PID: " + pID;
				SFRMProcessor.core.log.error(SFRMLog.IMH_CALLER + err);
				throw new MalformedURLException(err);					
			}														
		} catch (Exception e) {
			SFRMProcessor.core.log.error(SFRMLog.IMH_CALLER + "Partnership check failed: " + incomingMessage, e);
			throw new MalformedURLException("Partnership check failed");
		}
		return partnershipDVO;
	}
			
	/**
	 * Validate whether the incoming segment message has been 
	 * received once.
	 * 
	 * @param incomingMessage
	 * 			The incoming SFRM message.
	 * @return
	 * 			true if it is a duplicated (received already).
	 * @since
	 * 			1.0.1
	 * @throws DAOException
	 * 			throw if there is any kind of database error.
	 */
	public boolean 
	isDuplicateSegment(SFRMMessage incomingMessage) throws DAOException 
	{
		return (SFRMProcessor.getMessageSegmentHandler()
				.retrieveMessageSegment(
					incomingMessage.getMessageID(),
					SFRMConstant.MSGBOX_IN,
					incomingMessage.getSegmentNo(),
					incomingMessage.getSegmentType()) != null); 
	}
	
	/**
	 * Validate whether the associated message 
	 * of this incoming segment is processing.<br><br>
	 * 
	 * Any state except {@link SFRMConstant#MSGS_PROCESSING}
	 * return false for this method invocation.<br><br>
	 *  
	 * If the message record does not exist in receiver,
	 * we treat this as failure because 
	 * every segment should has handshaking steps before
	 * sending. 
	 * 
	 * @param incomingMessage
	 * 			The incoming SFRM message.
	 * @return
	 * 			true if it is processing.
	 * @since
	 * 			1.0.2
	 * @throws DAOException
	 * 			throw if there is any kind of database error.
	 * @throws SFRMMesageException 			
	 */
	public boolean 
	isProcessingMessage(SFRMMessage incomingMessage)
		throws DAOException, SFRMMessageException
	{
		// Retrieve the message record.
		SFRMMessageDVO msgDVO = null;
		SFRMMessageClassifier mc = incomingMessage.getClassifier();
		
		// TODO: 1. Refactoring
		// TODO: 2. Can we use session instead query db each time?
		if (mc.isMeta() || mc.isPayload())		
			msgDVO = SFRMProcessor.getMessageHandler()
				.retrieveMessage(
					incomingMessage.getMessageID(),
					SFRMConstant.MSGBOX_IN);
		else if (mc.isReceipt() || mc.isRecovery())
			msgDVO = SFRMProcessor.getMessageHandler()
				.retrieveMessage(
					incomingMessage.getMessageID(),
					SFRMConstant.MSGBOX_OUT);
		else{
			SFRMProcessor.core.log.fatal(
				SFRMLog.IMH_CALLER
			  +"Segment Type checked in processing message validation.");
			throw new SFRMMessageException(
			   "Invalid Segment Type: " + incomingMessage.getSegmentType());			   
		}
					
		if (msgDVO != null){			
			// if the status not equal to PR or HS, then the 
			// message is still not considered as failure.
			String status = msgDVO.getStatus();		
			if ( status.equals(SFRMConstant.MSGS_PROCESSING) ||
				 status.equals(SFRMConstant.MSGS_SEGMENTING) ||
				 // FIXME: Hot fix for 26/12/2006 Release
				 status.equals(SFRMConstant.MSGS_PROCESSED)	 ||
				 status.equals(SFRMConstant.MSGS_UNPACKAGING)||
				 status.equals(SFRMConstant.MSGS_HANDSHAKING) )
				return true;
			else
				SFRMProcessor.core.log.fatal(
					  SFRMLog.IMH_CALLER
				   + "The status of message is not processing."
				   +" It is "  + msgDVO.getStatus()
				   +" due to " + msgDVO.getStatusDescription());
		}		
		return false;
	}
	
	/**
	 * Validate whether the harddisk has enough space for this
	 * message.<br><br>
	 * 
	 * The validation formula is liked this:
	 * <PRE>	
	 * 	pS  : total payload size
	 * 	T   : threshold (the minimum hard disk space)
	 * 	HDDS: the remaining hard disk space
	 * 
	 *	true iff (HDDS >= pS + T)
	 *	false iff (HDDS < ps + T)
	 *  </PRE>
	 * 
	 * @param incomingMessage
	 * 			The incoming SFRM message. 
	 * @param threshold 
	 * 			The remaining disk space threshold. if 
	 * 			the remaining disk space is lower than (this value
	 * 			+ the payload size), in this case, it always return 
	 * 			false. 
	 * @since	
	 * 			1.0.2
	 * @return
	 * 			true if there is enough hard disk space or
	 * 			the associated payloads is created already 
	 * 			in the harddisk. vice versa. 			 			  
	 */
	public boolean 
	isNotEnoughRoom(SFRMMessage incomingMessage, long threshold)
		throws Exception
	{
		// Check whether the payloads has been existed or not. 
		PackagedPayloads pp = (PackagedPayloads) SFRMProcessor
			.getIncomingPayloadRepository().createPayloads
				(new Object[]{incomingMessage.getPartnershipId()
							 ,incomingMessage.getMessageID()}
				,PayloadsState.PLS_UPLOADING);	
		
		if (pp.getRoot().exists())
			return false;
		// FIXME: Performance issue : OSManager.
		// Get how much disk space left from the incoming payload repository.
		long freespace = SFRMProcessor.getOSManager()
			.getDiskFreespace(SFRMProcessor
			.getIncomingPayloadRepository()
			.getRepositoryPath(), null);
		long reqspace  = incomingMessage.getTotalSize() + threshold;
		// There is enough space, return false.
		if (freespace > reqspace)
			return false;			
		// Log error.
		SFRMProcessor.core.log.error(
			  SFRMLog.IMH_CALLER
		   + "Free Diskspace not enough : "
		   +" Remaining "
		   +  freespace
		   +" bytes, requires "
		   +  reqspace
		   +" bytes.");		
		return false;
	}
			
	/**
	 * Unpack the SMIME (secure MIME) message to become raw SFRM Message.<br><br>
	 *   
	 * @param incomingMessage
	 * 			The incoming SFRM Message.
	 * @param partnershipDVO
	 * 			The partnership to valid against to.
	 * @return
	 * 			The raw SFRM Message.  
	 * @throws SFRMMessageException  
	 * @throws UnrecoverableKeyException 
	 * @throws NoSuchAlgorithmException 
	 * @since
	 * 			1.5.0
	 * @throws Exception
	 * 			any kind of exceptions.
	 */
	public SFRMMessage unpackIncomingMessage(SFRMMessage message, SFRMPartnershipDVO partnershipDVO) 
		throws SFRMMessageException, NoSuchAlgorithmException, UnrecoverableKeyException {
		
		String logInfo = " msg id: " + message.getMessageID() 
						+" and sgt no: " + String.valueOf(message.getSegmentNo());

		KeyStoreManager keyman = SFRMProcessor.getKeyStoreManager();
		
		// Encryption enforcement check and decrypt
		if (partnershipDVO.isInboundEncryptEnforced()) { 
			if (!message.isEncryptedContentType()) {
				SFRMProcessor.core.log.error(
					SFRMLog.IMH_CALLER + "Encryption enforcement check failed: " 
					 + message);
				throw new SFRMMessageException("Insufficient message security");
			} else {
				try {
					message.decrypt(keyman.getX509Certificate(), keyman.getPrivateKey());
				} catch (SFRMMessageException e) {
					SFRMProcessor.core.log.error(
						SFRMLog.IMH_CALLER + "Unable to decrypt " 
						   + message, e);
					throw e;
				}
			}
		}
			
		// Signing enforcement check and unpack verified signature
		if (partnershipDVO.isInboundSignEnforced()) {
			if (!message.isSignedContentType()) {
				SFRMProcessor.core.log.error(
					SFRMLog.IMH_CALLER + "Signature enforcement check failed: "
					   + message);
				throw new SFRMMessageException("Insufficient message security");
				
			} else {
				try {
					message.verify(partnershipDVO.getVerifyX509Certificate());
				} catch (SFRMMessageException e) {
					SFRMProcessor.core.log.error(
						SFRMLog.IMH_CALLER + "Unable to verify "  
							+ message, e);
					throw e;
				}
			}
		}
		
		// Log information
		try {
			SFRMProcessor.core.log.info(
				 SFRMLog.IMH_CALLER 
			  +  SFRMLog.UNPACK_SGT 
			  +  logInfo			    
			  +" with payload size : " 
			  + message.getBodyPart().getSize());
		} catch (MessagingException e) {
			throw new SFRMMessageException("Unable to get body part size");
		}
		  
		return message;
	}
	
	/**
	 * initalize the <strong>Guard</strong> so that there is <strong>ONLY ONE THREAD</strong>
	 * working per the <code>incomingMessage</code>.
	 * 
	 * @param incomgMessage		The incoming SFRM Message.
	 * @return
	 */
	public boolean 
	initGuardForSegment(final SFRMMessage incomingMessage)
	{		
		if (!segmentDoSHandler.enter(incomingMessage)){
			// Log illegal / duplicated working message at same working time.
			SFRMLogUtil.log(SFRMLog.IMH_CALLER, SFRMLog.ILLEGAL_SGT,
				incomingMessage.getMessageID(), incomingMessage.getSegmentNo());
			SFRMProcessor.core.log.debug(
				this.segmentDoSHandler.getResolvedKey(incomingMessage));
			return true;
		}
		return false;
	}
	
	/**
	 * <strong>Resolve</strong> the guard for the <code>incomingMessage</code> to 
	 * the new owner (another thread that process the <code>incomingMessage</code>.
	 * 
	 * @param incomingMesasge	The incoming SFRM Message.
	 * @return
	 */
	public boolean
	resolveGuardOwnerForSegment(final SFRMMessage incomingMessage)
	{
		synchronized(this.segmentDoSHandler){			
			if (!this.segmentDoSHandler.exit(incomingMessage)){
				SFRMLogUtil.log(SFRMLog.IMH_CALLER, SFRMLog.RESOLVE_FAIL,
						incomingMessage.getMessageID(), incomingMessage.getSegmentNo());
				return false;
			}			
			this.segmentDoSHandler.enter(incomingMessage);
		}
		return true;
	}
	
	/**
	 * <strong>Release</strong> the ONE THREAD working GUARD for <code>incomingMessage</code>
	 * 
	 * @param incomingMessage	The incoming SFRM Message.
	 * @return
	 */
	public boolean
	releaseGuardForSegment(final SFRMMessage incomingMessage)
	{
		return this.segmentDoSHandler.exit(incomingMessage);
	}
	
	/**
	 * <strong>Process all kind of incoming SFRM message.</strong><br><br>.
	 * 
	 * This method is invoked when the received HTTP request is transformed
	 * to SFRM Message from the SFRM inbound listener.<br><br>
	 * 
	 * @param incomingMessage  	The incoming SFRM Message.
	 * @param params 			RESERVED
	 *  
	 * @return  A SFRM message for response message.
	 * @since  	1.0.0
	 * @version 1.0.1 [26/6/2007 - 03/07/2007]  			
	 * @throws 	Exception
	 */	
	public SFRMMessage 
	processIncomingMessage(SFRMMessage incomingMessage, final Object[] params) 
		throws Exception 
	{
		// constant reference to incomingMessage.
		final SFRMMessage inputMessage = incomingMessage;
		// Verified and decrypted SFRM Message (segment).
		final SFRMMessage rawMessage;
		// Get the classifier. 
		final SFRMMessageClassifier mc = incomingMessage.getClassifier();
		// The associated partnership DVO 
		final SFRMPartnershipDVO pDVO;		
		
		try{
			// Log received Information.
			SFRMLogUtil.log(SFRMLog.IMH_CALLER,	SFRMLog.RECEIVE_SGT, inputMessage.getMessageID(), inputMessage.getSegmentNo());
						
			// ------------------------------------------------------------------------
			// Step 0: Pre-condition process
			//		0.0  - Atomic Thread working Barrier for incoming message.			
			//		0.1  - Extract partnership from DB or session.
			//		0.2  - Unpack (verify and decrypt) the segment.			
			//		0.3  - Handshaking processing [META] 
			// 		0.4  - Message Status validation (true if status == PS,HS,PR,ST,UK) 			
			// ------------------------------------------------------------------------

			// Step 0.0
			if (this.initGuardForSegment (inputMessage)) return null;
			
			pDVO 		= this.extractPartnership(inputMessage); 			// Step 0.1
			rawMessage 	= this.unpackIncomingMessage(inputMessage, pDVO); 	// Step 0.2
			
			if (mc.isMeta()) { // Step 0.3
				this.processHandshakingMessage(inputMessage, params);				
			} else { // Step 0.4
				boolean isProc = this.isProcessingMessage(inputMessage);
				if (!isProc) throw new SFRMMessageException("Message is not processing, ignore segments.");											
			}										
	
			// ------------------------------------------------------------------------
			// Step 1: Duplicate segment validation. [PAYLOAD ONLY]
			// 
			// 		TECHNICAL DECISION on putting duplication validation at here.
			//		Since hacker may try to get the receipt by pretending a segments
			//		(may be intercept through HTTP monitor). So it is better to  
			//		verify the signature before allowing the receipt can be re-send. 
			// ------------------------------------------------------------------------	
			if (mc.isPayload()){
				boolean isDuplicate = this.isDuplicateSegment(inputMessage);
				if (isDuplicate) return this.processDuplicateSegment(inputMessage, params);
			}
			
			// ------------------------------------------------------------------------
			// Step 2: Segment Processing, Dispatch it to a new thread.
			// ------------------------------------------------------------------------
			final IncomingMessageHandler owner = this;
			ActiveThread thread = null;
			try {
				thread = monitor.acquireThread();
				if (thread == null)	return null;
				thread.setTask(new ActiveTaskAdaptor() {
					
					public void execute() throws Exception 
					{
						try{
							// Re-assign the guard for inputMessage to this working thread.
							if (!owner.resolveGuardOwnerForSegment(inputMessage)) return;
							
							// Log spawn thread action.
							SFRMLogUtil.debug(SFRMLog.IMH_CALLER, SFRMLog.SPANNED_THRD, inputMessage.getSegmentType());
							
							if (mc.isPayload()){								
								// Write the segment into pages/disk.
								owner.processSegmentMessage	(inputMessage, rawMessage, params);
							} else if (mc.isReceipt()){ 															
								// Update the associated message/segment to final state.
								owner.processReceiptMessage	(rawMessage, pDVO, params);
							} else if (mc.isMeta()){								
								// pre-allocate the payload.
								owner.processMetaMessage	(rawMessage, pDVO, params);
							} else if (mc.isRecovery()){								
								// Repend the payload segment.
								owner.processRecoveryMessage(rawMessage, params);
							}
						} finally {
							// If everything goes fine, the guard is released by this working thread
							// and the process of this message is considered as completed.
							owner.releaseGuardForSegment(inputMessage);
						}
						// For DEBUG Purpose only
						SFRMProcessor.core.log.debug(SFRMLog.IMH_CALLER + "Message info" + inputMessage);						
					}
					
					public void 
					onFailure(Throwable e) 
					{
						SFRMProcessor.core.log.error(SFRMLog.IMH_CALLER + "Error", e); 
					}				
				});
				
				thread.start();
			} 
			catch (Throwable e) {			
				monitor.releaseThread(thread);
			}
		}
		catch(Exception ex){
			// Release the guard when encountering exception.
			this.releaseGuardForSegment(inputMessage);
			throw ex; // Re-throw;
		}
		return null;
	}
	
	// ------------------------------------------------------------------------
	// Receipt Related Method
	// ------------------------------------------------------------------------
	
	/**
	 * Create a receipt according to the <code>rawMessage</code> only when necessary.<br><br>
	 * 
	 * The receipt is a empty content SFRM Message that segment type can be 
	 * <strong>RECEIPT</strong>, 
	 * <strong>LAST_RECEIPT</strong> and 
	 * <strong>RECOVERY</strong>.<br><br>
	 * 
	 * For the first two type, we call them 
	 * <strong>POSITIVE ACKNOWLEDGMENT</strong>.<br>
	 * For the last one, we call them 
	 * <strong>NEGATIVE ACKNOWLEDGMENT</strong> because 
	 * the sender will attempt to send that segment part again
	 * to the receiver and let receiver try to process again.
	 * 
	 * @param inputMessage
	 * 			The incoming SFRM Message for creating receipt.
	 * @param isPositive
	 * 			whether the receipt is positive or not.
	 * @param isLastReceipt
	 * 			if the <code>isPositive</code> is set to true, 
	 * 			the flag indicate whether it is a last receipt or not. 
	 * @since 1.0.1
	 * @throws DAOException
	 * 			throw if the receipt message segment record can not be created.
	 */
	public SFRMMessageSegmentDVO 
	createReceiptIfNecessary(
			SFRMMessage inputMessage,
			boolean 	isPositive, 
			boolean 	isLastReceipt) throws 
			DAOException, 
			SFRMMessageException 
	{	
		// Here we has used the trick that we don't want create
		// a new message object because we only need to create the 
		// message segment record here. so we keep using the 
		// raw message and changes some necessary information
		// , then save it to the database. Rollback to original
		// status is required for preserving the raw message 
		// original header.
		
		// Fill up the necessary information for the receipt
		String oriType	= inputMessage.getSegmentType();
		String type 	= null;
		if (isPositive && isLastReceipt){
			type = SFRMConstant.MSGT_LAST_RECEIPT;
		} else if (isPositive && !isLastReceipt){
			type = SFRMConstant.MSGT_RECEIPT;
		} else if (!isPositive){
			type = SFRMConstant.MSGT_RECOVERY;
		} 		
		
		inputMessage.setSegmentType(type);						
		// Create the message segment record.
		SFRMMessageSegmentHandler msHandle  = SFRMProcessor
			.getMessageSegmentHandler();
		// Find if there is any receipt created already.
		SFRMMessageSegmentDVO segReceiptDVO = 
			msHandle.retrieveMessageSegment(
				inputMessage, SFRMConstant.MSGBOX_OUT);
		
		if (segReceiptDVO != null){
			segReceiptDVO.setStatus(SFRMConstant.MSGS_PENDING);
			msHandle.getDAOInstance().persist(segReceiptDVO);
		}
		else{
			msHandle.createMessageSegmentBySFRMMessage(inputMessage,
				SFRMConstant.MSGBOX_OUT, SFRMConstant.MSGS_PENDING);
		}			 
		// Rollback the changes.
		inputMessage.setSegmentType(oriType);
		return segReceiptDVO;
	}

	/**
	 * 
	 * @param inputMessage
	 * @param params
	 * @return
	 * 
	 * @since	
	 * 			1.0.2
	 * @throws 
	 */
	public SFRMMessage 
	processReceiptAction(
			SFRMMessage 	inputMessage,
			final Object[] 	params) throws 
			Exception 
	{
		// --------------------------------------------------------------------
		// Pre	 : local Variable declaration
        // --------------------------------------------------------------------
		String mID = inputMessage.getMessageID();
		
		// Get the message handle.
		SFRMMessageSegmentHandler msHandle = SFRMProcessor
			.getMessageSegmentHandler();
		SFRMMessageHandler mHandle = SFRMProcessor
			.getMessageHandler();
		SFRMMessageDVO msgDVO = (SFRMMessageDVO) 
			mHandle.retrieveMessage(mID, SFRMConstant.MSGBOX_IN);
		
	    if (msgDVO != null){
	        int numOfSegment = msHandle.retrieveMessageSegmentCount(
	        	mID,
				SFRMConstant.MSGBOX_IN, 
				SFRMConstant.MSGT_PAYLOAD,
				SFRMConstant.WILDCARD);
	        
	        // Get how many segments are received.
	        // If we have received all,
	        // set the status of main message to pending for other proces.
	        if (numOfSegment == msgDVO.getTotalSegment()){
	        	SFRMProcessor.core.log.info(
	        		  SFRMLog.IMH_CALLER
		           +  SFRMLog.RECEIVE_ALL
	        	   +" msg id: " 
	        	   +  inputMessage.getMessageID());
	        	
	        	// Create an payload proxy object.
	        	PackagedPayloads pp = (PackagedPayloads) SFRMProcessor
					.getIncomingPayloadRepository().createPayloads(
						new Object[]{ inputMessage.getPartnershipId(), mID }
					   ,PayloadsState.PLS_UPLOADING);
	        	
	        	if (!pp.setToPending())
	        		SFRMProcessor.core.log.error(
	        			"Can not rename the payload : " 
	        		   + pp.getOriginalRootname() 
	        		   +" to PENDING."); 
	        	
	        	this.createReceiptIfNecessary(inputMessage, true, true);
	        	return null;   
	        }	        
	    }
	    this.createReceiptIfNecessary(inputMessage, true, false);
	    return null;
	}

	// ------------------------------------------------------------------------
	// Message Processing Method
	// ------------------------------------------------------------------------
	
	/**
	 * Process all message that are received once already.<br><br>
	 * 
	 * [SINGLE-THREADED].
	 * 
	 * The system retreives the receipt from the database and 
	 * set the status to <strong>MSGS_PENDING</strong> again. 
	 * so the receipt will send again thru the outbound.<br><br> 
	 * 
	 * If the receipt does not exist in the database. This method
	 * basically do nothing because we consider the receipt 
	 * will be generated by some working thread (i.e. The receipt 
	 * hasn't been sent yet).
	 * 
	 * @param rawMessage
	 * 			  The RAW SFRM Message. (not yet unsign and de-crypt)
	 * @param params
	 * 			  RESERVED.
	 * @return
	 * 			  RESERVED. 
	 * @since	
	 * 			  1.0.1 
	 */
	public SFRMMessage 
	processDuplicateSegment(
			SFRMMessage 		rawMessage,
			final Object[] 		params) throws 
			DAOException, 
			SFRMMessageException 
	{
		// --------------------------------------------------------------------
		// Pre	 : local Variable declaration
        // --------------------------------------------------------------------
		String mID 	= rawMessage.getMessageID();
		int	sNo 	= rawMessage.getSegmentNo();
		
		String logInfo = " msg id: "       + mID
						+" and sgt no: "   + sNo
						+" and sgt type: " + rawMessage.getSegmentType(); 
		
		// Log information.
        SFRMProcessor.core.log.info(SFRMLog.IMH_CALLER + SFRMLog.RECEIVE_DUP + logInfo);
        // --------------------------------------------------------------------
		// Step 0: Get a acknowledgment message and re-send back to sender. 
        // -------------------------------------------------------------------- 
        SFRMMessageSegmentHandler msHandle = 
        	SFRMProcessor.getMessageSegmentHandler();
        SFRMMessageSegmentDVO segReceiptDVO = 
        	msHandle.retrieveMessageSegment(mID, SFRMConstant.MSGBOX_OUT,
        									sNo, SFRMConstant.MSGT_RECEIPT);
        
        // If the receipt for this segment is null, we consider it is         
        // processing by some other thread to generate the async ack 
        // FIXME: any other case here?
        if (segReceiptDVO != null){
        	segReceiptDVO.setStatus(SFRMConstant.MSGS_PENDING);
        	msHandle.getDAOInstance().persist(segReceiptDVO);
        } else{
        	SFRMProcessor.core.log.warn(
        		  SFRMLog.IMH_CALLER
        	   + "Receipt does not found for duplicated segment: "
        	   +  logInfo);
        }
		return null;
	}
	
	/**
	 * Process all receipt-typed message.<br><br>
	 * 
	 * [MULTI-THREADED].<br>
	 * 
	 * What the method has done:<br>
	 * <ul>
	 * 	<li>Retrieve the corresponding <strong>META / PAYLOAD</strong>
	 * 		segment record from the DB from the receipt. </li> 
	 * 	<li>Update the status of this record to PROCESSED.</li>
	 * 	<li>If it is the last receipt,
	 * 		<ul>
	 * 			<li>Retrieve the Message record from the db.</li>
	 * 			<li>Update the Message record to PROCESSED.</li>
	 * 			<li>Get the payload cache from the outgoing segment
	 * 				repository and session from session manager,
	 * 				then delete it.</li>
	 * 		</ul>
	 * 	</li>	
	 * </ul> 
	 * 
	 * @param inputMessage
	 * 			The incoming SFRM Message (unsigned and decrpyted)
	 * @param partnershipDVO
	 * 			The partnership DVO for this incoming message. 			
	 * @param params
	 * 			RESERVED. 
	 * @return
	 * @since	
	 * 			1.0.1
	 * @throws DAOException
	 * 			any kind of DB I/O Errors. 
	 */
	public SFRMMessage 
	processReceiptMessage(
			SFRMMessage 		inputMessage, 
			SFRMPartnershipDVO 	partnershipDVO, 
			final Object[] 		params)	throws 
			DAOException
	{		
        // --------------------------------------------------------------------
		// Pre	 : local Variable declaration
        // -------------------------------------------------------------------- 
		String 	mID = inputMessage.getMessageID();
		int		sNo	= inputMessage.getSegmentNo();
        // --------------------------------------------------------------------
		// Step 0: Retrieve the message segment record in the database.
        // --------------------------------------------------------------------		
        SFRMMessageSegmentHandler msHandle = SFRMProcessor
			.getMessageSegmentHandler();
        SFRMMessageSegmentDVO segDVO = msHandle
        	.retrieveMessageSegment(mID, SFRMConstant.MSGBOX_OUT,
        							sNo, SFRMConstant.MSGT_PAYLOAD);
        
		if (segDVO == null){
        	// Log error information.
        	SFRMProcessor.core.log.error(
        		  SFRMLog.IMH_CALLER
        	   + "Missing outgoing segment record with" 
        	   +" msg id: " +  mID 
       		   +" and sgt no: " +  sNo
       		   +" for receipt confirmation.");
        	throw new NullPointerException(
				"Missing Segment record for receipt confirmation.");
        }        
        // --------------------------------------------------------------------
        // Step 1: Set the status of this message segment to processed 
        // 		   because the receipt has been received.
        // --------------------------------------------------------------------
		Timestamp currentTime = new Timestamp(System.currentTimeMillis());
		
        segDVO.setStatus(SFRMConstant.MSGS_PROCESSED);
        segDVO.setCompletedTimestamp(currentTime);        
        msHandle.getDAOInstance().persist(segDVO);
        
        // --------------------------------------------------------------------        
        // Step 2: Check whether the message segment is last receipt, if 
        //         yes, update the message status to PROCESSED and 
        //		   delete the outbound packaged payload, and all sesssions.
        // --------------------------------------------------------------------                      
        if (inputMessage.getIsLastReceipt()){
            SFRMMessageHandler mHandle = SFRMProcessor.getMessageHandler();
        	// Log information.
        	SFRMProcessor.core.log.info(
        		 SFRMLog.IMH_CALLER 
        	   + SFRMLog.SEND_ALL
        	   + "********** CONVERSION DONE ********* with msg id: " 
        	   + mID); 		
        	
        	// Retrieve from the cache / DB.
        	SFRMMessageDVO msgDVO = mHandle.retrieveMessage(mID, SFRMConstant.MSGBOX_OUT);        	
        	        	        	        	
        	// Only update the message if the status is not processed.
        	if (!msgDVO.getStatus().equals(SFRMConstant.MSGS_PROCESSED)){        	        	
        		 msgDVO.setStatus			 (SFRMConstant.MSGS_PROCESSED);
        		 msgDVO.setStatusDescription (SFRMConstant.MSGSDESC_PROCESSED);
        		 msgDVO.setCompletedTimestamp(new Timestamp(System.currentTimeMillis()));
        		 mHandle.updateMessage(msgDVO);
        		 // Clear the message & partnership cache.
        		 mHandle.clearCache(msgDVO);
        		 SFRMProcessor.getPartnershipHandler().clearCache(
        			msgDVO.getPartnershipId(), 
        			msgDVO.getMessageId());
        	}        	
        	
        	// Prevent any Mapped Memory region that hold the payload.
    		// System.gc();
    		
        	// Clear the packaged payload.
        	PackagedPayloads pp = (PackagedPayloads) 
        		SFRMProcessor.getPackagedPayloadRepository()
        		.getPayload(new Object[]{msgDVO.getPartnershipId(),
        								 mID},
        					PayloadsState.PLS_PENDING);        	
        	if (pp != null)
        		pp.clearPayloadCache();
        	
        	//Clear the folder payload
        	FoldersPayload dir = (FoldersPayload) SFRMProcessor.getOutgoingPayloadRepository().getPayload(new Object[]{inputMessage.getPartnershipId(), mID}, PayloadsState.PLS_PROCESSED);
        	if(dir != null){
        		dir.clearPayloadCache();
        	}
        	
        }        
        return null;
	}	
		
	/**
	 * Process handshaking for a new message.<br><br>
	 * 
	 * [SINGLE-THREADED].<br><br>
	 * 
	 * The message segment is also <strong>META</strong>
	 * type.<br><br>
	 * 
	 * In the handshaking steps, it create the 
	 * message record and check whether it 
	 * has enough space for receiving the message.<br><br>
	 *  
	 * This method does not block and return 
	 * immediately to let the sender know does the 
	 * receiver is available to receive this message.
	 * 
	 * @param rawMessage
	 * 			The incoming SFRM Message. 
	 * @param params
	 * 			RESERVED.
	 * @return  
	 * 			RESERVED.
	 * @since	
	 * 			1.0.3 			   
	 * @throws DAOException
	 * 			any kind of DB I/O Errors.
	 * @throws Exception
	 * 			thrown when pre-allocate the payload.  			
	 */
	public SFRMMessage 
	processHandshakingMessage(
			SFRMMessage 		rawMessage,
			final Object[] 		params)	throws 
			Exception
	{ 
		// --------------------------------------------------------------------
		// Pre	 : local Variable declaration
        // -------------------------------------------------------------------- 
		String 	mID 		= rawMessage.getMessageID();
		long	totalSize 	= rawMessage.getTotalSize(); 
		String logInfo = " msg id: " 		 + rawMessage.getMessageID()
						+" and total size: " + rawMessage.getTotalSize();
		
		// Log information
		SFRMProcessor.core.log.info(SFRMLog.IMH_CALLER + SFRMLog.RECEIVE_HDSK + logInfo);		
		// -----------------------------------------------------------------
		// Step 0: create the sfrm message record.
		// -----------------------------------------------------------------
		
		// Retrieve the message record if any.		
		// This query is used for special handling when the sender(S) 
		// is inproperly shutdown after sending the handshaking request
		// to recever. The receiver may have inserted a new Message
		// instance. Then, when the sender re-sends the handshaking 
		// request, the receiver will delete the existing one and 
		// create again. WHY WE DO NOT USE the existing row because
		// we don't assure that the setting of that message is 
		// still identical to the one in the (R) database. like 
		// signing and encryption setting.
		
        SFRMMessageHandler mHandle = SFRMProcessor.getMessageHandler();
        SFRMMessageDVO msgDVO = mHandle.retrieveMessage(mID, SFRMConstant.MSGBOX_IN);        
        
        // Only remove message record when the message is still handshaking.
        if (msgDVO != null &&  msgDVO.getStatus().equals(SFRMConstant.MSGS_HANDSHAKING)){
        	SFRMProcessor.core.log.info(
        		  SFRMLog.IMH_CALLER
        	   + "Removing existing record with"
        	   +  logInfo);
        	mHandle.removeMessage(msgDVO);
        }
        else 
        if (msgDVO != null && !msgDVO.getStatus().equals(SFRMConstant.MSGS_HANDSHAKING)){	
        	throw new SFRMMessageException(
				"Message is not handshaking, invalid META segment received.");
        }        
        
        // Get the partnership record. 
        SFRMPartnershipDVO pDVO = this.extractPartnership(rawMessage);
        
        // Create a new message segment record.
        msgDVO = mHandle.createMessageBySFRMMetaMessage(
        		rawMessage,
        		pDVO,
        		SFRMConstant.MSGBOX_IN,
        		SFRMConstant.MSGS_HANDSHAKING,
        		SFRMConstant.MSGSDESC_HANDSHAKING);
        
        try{
    		// -----------------------------------------------------------------
            // Step 1: check how much disk free we left, if not enough,
            //		   throws IOException 
    		// -----------------------------------------------------------------
        	boolean isNotEnoughRoom = this.isNotEnoughRoom(
        		rawMessage, totalSize);		
        	
			if (isNotEnoughRoom)
				throw new IOException("Not enough hdd space to receive this message.");
    		// -----------------------------------------------------------------
            // Step 2: check if the payload exceeding file size limit, if yes
			//		   throws SFRMException
    		// -----------------------------------------------------------------
			long MPSize = SFRMProperties.getMaxPayloadSize(); 
			if (totalSize > MPSize)			
				throw new SFRMException(
					 "Payload Exceeding file size limit: "
				   +  totalSize
				   +" can allow file size under: "
				   +  MPSize);		
							
        } catch(Exception e){
        	SFRMProcessor.core.log.error(SFRMLog.IMH_CALLER + SFRMLog.FAIL_HDSK + "Reason: ", e); 
        	// Turn the message to fail.
			msgDVO.setStatus			(SFRMConstant.MSGS_DELIVERY_FAILURE);
			msgDVO.setCompletedTimestamp(new Timestamp(System.currentTimeMillis()));
			msgDVO.setStatusDescription	(e.toString());
			mHandle.updateMessage(msgDVO);
			mHandle.clearCache(msgDVO);
			SFRMProcessor.getPartnershipHandler().clearCache(msgDVO.getPartnershipId(), mID);
			throw e;
        }				
		return null;
	}	
	
	/**
	 * Process all meta-typed message segment.<br><br>
	 * 
	 * [MULTI-THREADED].<br><br>
	 * 
	 * This method pre-allocates the payload and it blocks  
	 * until the file has been created.    
	 * 
	 * @param inputMessage
	 * 			The incoming SFRM Message. (unsigned and decrypted)
	 * @param partnershipDVO 
	 * 			The partnership DVO for this incoming message.
	 * @param params
	 * 			RESERVED.
	 * @return  
	 * 			RESERVED.
	 * @since	
	 * 			1.0.0 			   
	 * @throws DAOException
	 * 			any kind of DB I/O Errors.
	 * @throws Exception
	 * 			thrown when pre-allocate the payload.  			
	 */
	public SFRMMessage 
	processMetaMessage(
			SFRMMessage 		inputMessage,
			SFRMPartnershipDVO 	partnershipDVO, 
			final Object[] 		params)	
	{
		// --------------------------------------------------------------------
		// Pre	 : local Variable declaration
        // -------------------------------------------------------------------- 
		String mID = inputMessage.getMessageID();
        // -----------------------------------------------------------------
		// Step 0: create the pre-allocated files for on the fly recv mode.
		// -----------------------------------------------------------------
		PackagedPayloads pp 		= null;
		SFRMMessageHandler mHandle 	= SFRMProcessor.getMessageHandler();
		SFRMMessageDVO msgDVO		= null;
		try{
			pp = (PackagedPayloads) SFRMProcessor
			 	.getIncomingPayloadRepository()
    			.createPayloads(new Object[]{inputMessage.getPartnershipId(), mID},
    							PayloadsState.PLS_UPLOADING); 			
			File payload = pp.getRoot();
			
			SFRMProcessor.getOSManager().createDummyFile(
				payload.getAbsolutePath(),
				inputMessage.getTotalSize(), 
				null);
			
			msgDVO = mHandle.retrieveMessage(inputMessage.getMessageID(), SFRMConstant.MSGBOX_IN);
			msgDVO.setStatus(SFRMConstant.MSGS_PROCESSING);
			mHandle.updateMessage(msgDVO);		
			
		}catch(Exception ioe){
	    	// --------------------------------------------------------------------
			// Alternative Path: Fail to create the dummy, set the message to DF			
			// --------------------------------------------------------------------
			SFRMProcessor.core.log.error(
				SFRMLog.IMH_CALLER 
			  + SFRMLog.RECEIVE_FAIL 
			  + "msg id: " 
			  +  mID, ioe);
			
			try{
				msgDVO = mHandle.retrieveMessage(mID, SFRMConstant.MSGBOX_IN);
				msgDVO.setStatus				(SFRMConstant.MSGS_DELIVERY_FAILURE);
				msgDVO.setCompletedTimestamp	(new Timestamp(System.currentTimeMillis()));
				msgDVO.setStatusDescription		(ioe.getMessage());
				mHandle.updateMessage(msgDVO);
				// Clear the cache also.
				if (pp != null)
					pp.clearPayloadCache();
				
				mHandle.clearCache(msgDVO);
				SFRMProcessor.getPartnershipHandler().clearCache(msgDVO.getPartnershipId(), mID);				
			}
			catch(DAOException daoe){
				SFRMProcessor.core.log.fatal(
					  SFRMLog.IMH_CALLER
				   + "Handshaking Mark failure for msg id: "
				   +  mID);
			}
		}        
		return null;
	}
	
	/**
	 * Process payload-typed segment message.<br><br>
	 * 
	 * What the method has done:<br>
	 * <ul>
	 * 	<li> Create a segment file in the incoming segment repository. </li>
	 * 	<li> Create a inbox message segment record for the incoming message. </li>
	 * </ul>
	 * @param inputMessage 
	 * 			  The packed SFRMMessage.
	 * @param rawMessage
	 *            The unpacked SFRM Message. (i.e. no sign and encrypt here) 
	 * @param params
	 *            RESERVED
	 * 
	 * @return A SFRM message for response message.
	 */
	public SFRMMessage 
	processSegmentMessage(
			SFRMMessage 	inputMessage,
			SFRMMessage 	rawMessage, 
			final Object[] 	params) throws 
			IOException,
			DAOException, 
			SFRMMessageException, 
			Exception 
	{
		// TODO: Tuning 
		String mId	= rawMessage.getMessageID();
		String pId	= rawMessage.getPartnershipId();
		String logInfo = "";
		try{						
	        // --------------------------------------------------------------------
			// Step 0: Create the physical payload into incoming segment repostory.
	        // --------------------------------------------------------------------
			String path = SFRMProcessor.getIncomingPayloadRepository().getRepositoryPath() + 
							File.separator + "~" + 
							pId + "$" + 
							mId + ".sfrm";
			File payload = new File(path);
	        
	        // --------------------------------------------------------------------        
	        // Step 1: CRC Check
	        // 		   if CRC check fail, return immediately with
	        //		   the negative receipt (recovery request).
	        // --------------------------------------------------------------------

			logInfo	= " msg id: " + mId  + " and sgt no: " + rawMessage.getSegmentNo();

	        String micValue = inputMessage.digest();
			if (!micValue.equalsIgnoreCase(rawMessage.getMicValue())){
				SFRMProcessor.core.log.info(
					  SFRMLog.IMH_CALLER 
				   +  SFRMLog.FAIL_CRC
				   +  logInfo 
				   +" Expected MIC: " 
				   +  rawMessage.getMicValue() 
				   +" Result MIC: " + micValue);
				throw new ChecksumException("Invalid CRC Value.");
			}else {
				SFRMProcessor.core.log.info(
					  SFRMLog.IMH_CALLER
				   +  SFRMLog.SUCCESS_CRC
				   +  logInfo);
			}
			
			OSManager osm = SFRMProcessor.getOSManager();
			if(osm.getOSName().equalsIgnoreCase("windows")){			
		        java.io.FileOutputStream fos = new java.io.FileOutputStream(payload, true);
		        FileChannel fc = fos.getChannel();
		        long offset = rawMessage.getSegmentOffset();
		        int length  = (int)rawMessage.getSegmentLength();
		        ReadableByteChannel rbc = Channels.newChannel(rawMessage.getInputStream());
		        fc.transferFrom(rbc, offset, length);
		        fc.force(true);
		        rbc.close();
		        fc.close();
		        fos = null;
		        fc = null;
		        rbc = null;
			}else{
				RandomAccessFile raf = new RandomAccessFile(payload, "rw");
				FileChannel fc 		 = raf.getChannel();		
				long offset = rawMessage.getSegmentOffset();
		        int length  = (int)rawMessage.getSegmentLength();        
		        MappedByteBuffer mbb = fc.map(
		        		FileChannel.MapMode.READ_WRITE
		        	   ,offset
		        	   ,length);
		        mbb.limit(length);
				mbb.position(0);
				InputStream ins = rawMessage.getInputStream();
		        ReadableByteChannel rbc = Channels.newChannel(ins);        
		        rbc.read(mbb);
		        rbc.close(); rbc = null;
		        fc.close();  fc  = null;
		        raf.close(); raf = null;
		        ins.close();
		        mbb = null;         
			}
	        
			/*
	        SFRMProcessor.core.log.info(" MEM   : " + Runtime.getRuntime().totalMemory());
	        System.gc();
	        System.runFinalization();
	        SFRMProcessor.core.log.info(" MEM GC: " + Runtime.getRuntime().totalMemory());
	        */
	        
			// Step 1: Create the message segment record in the database.
	        SFRMMessageSegmentHandler msHandle = 
	        	SFRMProcessor.getMessageSegmentHandler();
	        // Create a new message segment record.
			msHandle.createMessageSegmentBySFRMMessage(
				rawMessage,
				SFRMConstant.MSGBOX_IN, 
				SFRMConstant.MSGS_PROCESSING);		       
	
			// --------------------------------------------------------------------
			// Step 2: Retrieve the main message record
			//		   check whether all segment has been done.
			//		   also process how the receipt look like.
	        // --------------------------------------------------------------------	
			this.processReceiptAction(rawMessage, null);
		}
		catch(Exception e){
			// Create Recovery Message.
			SFRMProcessor.core.log.error(SFRMLog.IMH_CALLER + SFRMLog.RECEIVE_FAIL + logInfo, e);
			this.createReceiptIfNecessary(inputMessage, false, false);
		}
        return null;		
	}
	
	/**
	 * Process all recovery message.<br><br>
	 * 
	 * [MULTI-THREADED].<br><br>
	 * 
	 * @param rawMessage
	 * 			The unpacked SFRM Message. (i.e. no sign and encrypt here)
	 * @param params
	 * 			RESERVED
	 * @return
	 * @throws DAOException
	 */
	public SFRMMessage 
	processRecoveryMessage(
			SFRMMessage 	rawMessage,
			final Object[] 	params) throws 
			DAOException 
	{
		// ------------------------------------------------------------
		// Step 0: Retrieve the message segment record in the database.
		// ------------------------------------------------------------		
        SFRMMessageSegmentHandler msHandle = 
        	SFRMProcessor.getMessageSegmentHandler();
        SFRMMessageSegmentDVO segDVO = msHandle
        	.retrieveMessageSegment(
        		rawMessage.getMessageID(), 
        		SFRMConstant.MSGBOX_OUT, 
        		rawMessage.getSegmentNo(), 
        		SFRMConstant.MSGT_PAYLOAD);
        
        if (segDVO == null){
        	SFRMProcessor.core.log.error(
        		  SFRMLog.IMH_CALLER 
        	   + "Missing outgoing segment record with message id: " 
        	   +  rawMessage.getMessageID() 
       		   +" and segment no: " 
       		   +  rawMessage.getSegmentNo() 
       		   +" for receipt confirmation.");
        	throw new NullPointerException(
				"Missing Segment record for receipt confirmation.");
        }
		// ----------------------------------------------------------------
        // Step 1: Set the status of this message segment to PENDING 
        // 		   because the on-demand recovey request has been received.
		// ----------------------------------------------------------------
        segDVO.setStatus(SFRMConstant.MSGS_PENDING);
        msHandle.getDAOInstance().persist(segDVO);
        return null;
	}
	
	// ------------------------------------------------------------------------
	// Utility Logger Method
	// ------------------------------------------------------------------------
	
	/**
	 * Log the whole message for debug purpose.
	 * 
	 * @param incomingMessage
	 *            The incoming sfrm message.
	 */
	protected void 
	logMessage(SFRMMessage incomingMessage){
		// For DEBUG Purpose only
        SFRMProcessor.core.log.debug(
        	 SFRMLog.IMH_CALLER 
          + "msg info" 
          +  incomingMessage);
	}
		
	/**
	 * Log the message type for debug purpose.<br>
	 * <br>
	 * 
	 * The message type currently support for this version is 
	 * META, PAYLOAD ,RECEIPT and RECOVERY.
	 * 
	 * @param type
	 *            The message type.
	 */
	protected void 
	logMessageType(String type){
		// For DEBUG Purpose only
        SFRMProcessor.core.log.debug(
        	  SFRMLog.IMH_CALLER 
           +  SFRMLog.SPANNED_THRD 
           +" It is a sgt type of: " 
           +  type);        							 
	}
}

