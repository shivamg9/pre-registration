package io.mosip.kernel.synchandler.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;

import io.mosip.kernel.synchandler.service.SyncConfigDetailsService;
/**
 * 
 * @author Srinivasan
 *
 */
@RestController
public class SyncConfigDetailsController {

	/**
	 * 
	 */
	@Autowired
	SyncConfigDetailsService syncConfigDetailsService;
	/**
	 * 
	 * @return JSONNode - global config response 
	 */
	@GetMapping(value="/globalconfigs")
	public JsonNode getGlobalConfigDetails() {
		return syncConfigDetailsService.getEnrolmentClientConfigDetails();
	}
	/**
	 * 
	 * @return
	 */
	@GetMapping(value="/registrationcenterconfig/{registrationcenterid}")
	public JsonNode getRegistrationCentreConfig(@PathVariable(value="registrationcenterid") String regId) {
		return syncConfigDetailsService.getAdminConfigDetails(regId);
	}
	
}
