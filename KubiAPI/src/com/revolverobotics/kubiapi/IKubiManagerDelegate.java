package com.revolverobotics.kubiapi;

import java.util.ArrayList;


/**
 * This interface provides notifications about the status of KubiManager as well as the status of the connection to a kubi and the result of a kubi search.
 * @author Oliver Rice
 * 
 */
public interface IKubiManagerDelegate
{
	/**
	 * Called when KubiManager.findKubi() successfully finds a kubi. This method allows the delegate to decide whether to connect to the found kubi or not. 
	 * To connect simply call manager.connectToKubi(device).
	 * @param manager The KubiManager that found the device.
	 * @param device The result of the kubi search
	 */
	public void kubiDeviceFound(KubiManager manager, KubiSearchResult device);
	
	
	/**
	 * Called when the status of the KubiManager changes.
	 * @param manager The KubiManager that changed its status
	 * @param oldStatus The status that the KubiManager had previous to the change.
	 * @param newStatus The new status of the KubiManager
	 */
	public void kubiManagerStatusChanged(KubiManager manager, int oldStatus, int newStatus);
	
	
	/**
	 * Called when a problem occurred, causing the previous action to fail.
	 * @param manager The KubiManager that experienced the problem.
	 * @param reason The reason for the failure
	 */
	public void kubiManagerFailed(KubiManager manager, int reason);
	
	
	/**
	 * Called when the findAllKubis() command has completed successfully. This will be called at least 2 seconds after calling findAllKubis().
	 * @param manager The KubiManager that completed the scan.
	 * @param device The list of devices found
	 */
	public void kubiScanComplete(KubiManager manager, ArrayList<KubiSearchResult> device);
}
