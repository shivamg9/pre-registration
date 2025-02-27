package io.mosip.preregistration.application.service;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import javax.annotation.PostConstruct;
import javax.transaction.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.afterburner.AfterburnerModule;

import io.mosip.preregistration.application.dto.OtpRequestDTO;
import io.mosip.preregistration.application.repository.ApplicationRepostiory;
import io.mosip.preregistration.core.common.entity.ApplicationEntity;
import org.json.JSONException;
import org.json.simple.parser.ParseException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.multipart.MultipartFile;

import io.mosip.kernel.core.authmanager.authadapter.model.AuthUserDetails;
import io.mosip.kernel.core.exception.ExceptionUtils;
import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.preregistration.application.code.NotificationRequestCodes;
import io.mosip.preregistration.application.dto.NotificationResponseDTO;
import io.mosip.preregistration.application.errorcodes.NotificationErrorCodes;
import io.mosip.preregistration.application.errorcodes.NotificationErrorMessages;
import io.mosip.preregistration.application.exception.BookingDetailsNotFoundException;
import io.mosip.preregistration.application.exception.DemographicDetailsNotFoundException;
import io.mosip.preregistration.application.exception.MandatoryFieldException;
import io.mosip.preregistration.application.exception.RestCallException;
import io.mosip.preregistration.application.exception.util.NotificationExceptionCatcher;
import io.mosip.preregistration.application.service.util.NotificationServiceUtil;
import io.mosip.preregistration.core.code.AuditLogVariables;
import io.mosip.preregistration.core.code.EventId;
import io.mosip.preregistration.core.code.EventName;
import io.mosip.preregistration.core.code.EventType;
import io.mosip.preregistration.core.common.dto.AuditRequestDto;
import io.mosip.preregistration.core.common.dto.BookingRegistrationDTO;
import io.mosip.preregistration.core.common.dto.DemographicResponseDTO;
import io.mosip.preregistration.core.common.dto.KeyValuePairDto;
import io.mosip.preregistration.core.common.dto.MainRequestDTO;
import io.mosip.preregistration.core.common.dto.MainResponseDTO;
import io.mosip.preregistration.core.common.dto.NotificationDTO;
import io.mosip.preregistration.core.config.LoggerConfiguration;
import io.mosip.preregistration.core.util.AuditLogUtil;
import io.mosip.preregistration.core.util.NotificationUtil;
import io.mosip.preregistration.core.util.ValidationUtil;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.mosip.preregistration.core.exception.InvalidRequestParameterException;

import io.mosip.preregistration.core.common.dto.ExceptionJSONInfoDTO; // Import this

@Service
@EnableScheduling
public class NotificationService {

	private static final Logger log = LoggerConfiguration.logConfig(NotificationService.class);

	private final ApplicationRepostiory applicationRepository;
	private final NotificationServiceUtil serviceUtil;
	private final DemographicServiceIntf demographicService;
	private final ValidationUtil validationUtil;
	private final ObjectMapper objectMapper;

	private final String notificationServiceId;
	private final String version;
	private final String mosipDateTimeFormat;
	private final String emailField;
	private final String phoneField;
	private final String fullNameField;
	private final String nameFormat;
	private final String identity;
	private final String emailSubjectTemplate;
	private final String emailContentTemplate;
	private final String smsTemplate;


	private final Map<String, String> requiredRequestMap = new HashMap<>();
	private final String defaultLanguage; // Add a default language


	@Autowired
	public NotificationService(ApplicationRepostiory applicationRepository,
							   NotificationServiceUtil serviceUtil,
							   DemographicServiceIntf demographicService,
							   ValidationUtil validationUtil,
							   ObjectMapper objectMapper,
							   @Value("${mosip.pre-registration.notification.id}") String notificationServiceId,
							   @Value("${version}") String version,
							   @Value("${mosip.utc-datetime-pattern}") String mosipDateTimeFormat,
							   @Value("${preregistration.identity.email}") String emailField,
							   @Value("${preregistration.identity.phone}") String phoneField,
							   @Value("${preregistration.identity.name}") String fullNameField,
							   @Value("${preregistration.notification.nameFormat}") String nameFormat,
							   @Value("${preregistration.identity}") String identity,
							   @Value("${preregistration.default-language:eng}") String defaultLanguage,
							   @Value("${preregistration.notification.reminder.mail.subject.template}") String emailSubjectTemplate,
							   @Value("${preregistration.notification.reminder.mail.content.template}") String emailContentTemplate,
							   @Value("${preregistration.notification.reminder.sms.template}") String smsTemplate) {

		this.applicationRepository = applicationRepository;
		this.serviceUtil = serviceUtil;
		this.demographicService = demographicService;
		this.validationUtil = validationUtil;
		this.objectMapper = objectMapper;
		this.notificationServiceId = notificationServiceId;
		this.version = version;
		this.mosipDateTimeFormat = mosipDateTimeFormat;
		this.emailField = emailField;
		this.phoneField = phoneField;
		this.fullNameField = fullNameField;
		this.nameFormat = nameFormat;
		this.identity = identity;
		this.defaultLanguage = defaultLanguage; // Store the default language
		this.emailSubjectTemplate = emailSubjectTemplate;
		this.emailContentTemplate = emailContentTemplate;
		this.smsTemplate = smsTemplate;
	}

	@PostConstruct
	public void setup() {
		requiredRequestMap.put("version", version);
		requiredRequestMap.put("id", notificationServiceId);
		log.info("NotificationService setup complete.  requiredRequestMap: {}", requiredRequestMap);
	}

	@Scheduled(cron = "0 45 16 * * ?") // Runs at 6:00 PM every day.  Correct cron.
	@Transactional
	public void sendAppointmentReminders() {
		log.info("Starting appointment reminder task.");

		LocalDate tomorrow = LocalDate.now(ZoneId.of("UTC")).plusDays(4);
		List<ApplicationEntity> applications = applicationRepository.findByAppointmentDate(tomorrow);

		if (applications == null || applications.isEmpty()) {
			log.info("No appointments found for tomorrow.");
			return;
		}

		log.info("Found {} appointments for tomorrow.", applications.size());

		for (ApplicationEntity application : applications) {
			try {
				log.info("Processing appointment reminder for Prid: {}", application.getApplicationId());
				processSingleReminder(application);
			} catch (Exception e) {
				log.error("Failed to send notification for pre-registration ID: {}", application.getApplicationId(), e);
				// Consider:  Adding a retry mechanism or dead-letter queue.
			}
		}
		log.info("Appointment reminder task completed.");
	}


	private void processSingleReminder(ApplicationEntity application) throws Exception {
		MainResponseDTO<DemographicResponseDTO> demographicResponse = demographicService.getDemographicData(application.getApplicationId());

		if (demographicResponse.getResponse() == null) {
			log.error("Demographic response is null for pre-registration ID: {}", application.getApplicationId());
			if (demographicResponse.getErrors() != null) {
				log.error("Errors in demographic data: {}", demographicResponse.getErrors());
			}
			return; // Exit this application's processing
		}


		DemographicResponseDTO demographicData = demographicResponse.getResponse();
		String langCode = demographicData.getLangCode() != null ? demographicData.getLangCode() : defaultLanguage; // Use default language if null

		NotificationDTO notificationDto = buildNotificationDTO(application, demographicData);

		Map<String, Object> request = buildNotificationRequest(notificationDto);
		String jsonRequest = objectMapper.writeValueAsString(request); // Convert to JSON *once*

		sendNotification(jsonRequest, langCode, null, false); // Use the public method
	}



	private NotificationDTO buildNotificationDTO(ApplicationEntity application, DemographicResponseDTO demographicData) throws Exception {
		NotificationDTO notificationDto = new NotificationDTO();
		notificationDto.setPreRegistrationId(application.getApplicationId());
		notificationDto.setAppointmentDate(application.getAppointmentDate().toString());
		notificationDto.setAppointmentTime(application.getSlotFromTime().format(DateTimeFormatter.ofPattern("hh:mm a")));
		notificationDto.setAdditionalRecipient(false);
		notificationDto.setLangCode(demographicData.getLangCode() != null ? demographicData.getLangCode() : defaultLanguage); // Use default if null
		notificationDto.setIsBatch(false);

		// Handle JSON parsing safely
		JsonNode responseNode = objectMapper.readTree(demographicData.getDemographicDetails().toJSONString());
		if (responseNode.has(identity)) {
			responseNode = responseNode.get(identity);
		} else {
			log.error("Identity field '{}' not found in demographic data for pre-registration ID: {}", identity, application.getApplicationId());
			throw new InvalidRequestParameterException("DEMO_DATA_MISSING", "Identity field missing", null); // Or handle as appropriate
		}

		// Extract names safely
		if (responseNode.has(fullNameField) && responseNode.get(fullNameField).isArray()) {
			List<KeyValuePairDto<String, String>> namePairs = new ArrayList<>();
			for (JsonNode nameNode : responseNode.get(fullNameField)) {
				if (nameNode.has("language") && nameNode.has("value")) {
					//Corrected
					KeyValuePairDto<String,String> keyValuePairDto = new KeyValuePairDto<>();
					keyValuePairDto.setKey(nameNode.get("language").asText());
					keyValuePairDto.setValue(nameNode.get("value").asText());
					namePairs.add(keyValuePairDto);
				}
			}
			if(!namePairs.isEmpty()){
				notificationDto.setFullName(namePairs);
			}
			else{
				log.warn("No valid full name entries found for pre-registration ID: {}", notificationDto.getPreRegistrationId());
			}

		}
		else {
			log.warn("Full name field '{}' is missing or not an array in demographic data for pre-registration ID: {}", fullNameField, notificationDto.getPreRegistrationId());
		}



		// Extract email and phone safely
		notificationDto.setEmailID(responseNode.has(emailField) && !responseNode.get(emailField).isNull() ? responseNode.get(emailField).asText() : null);
		notificationDto.setMobNum(responseNode.has(phoneField) && !responseNode.get(phoneField).isNull() ? responseNode.get(phoneField).asText() : null);

		// Modify center name and address
		notificationDto = serviceUtil.modifyCenterNameAndAddress(notificationDto, application.getRegistrationCenterId(), demographicData.getLangCode());
		return notificationDto;
	}
	private List<ExceptionJSONInfoDTO> convertServiceErrors(Exception ex) {
		List<ExceptionJSONInfoDTO> errors = new ArrayList<>();
		ExceptionJSONInfoDTO error = new ExceptionJSONInfoDTO();
		error.setErrorCode("NOTIFICATION_ERROR"); //  set an appropriate error code
		error.setMessage(ex.getMessage()); //  set the exception message
		errors.add(error);
		return errors;
	}


	private Map<String, Object> buildNotificationRequest(NotificationDTO notificationDto) {
		Map<String, Object> innerRequest = new HashMap<>();
		innerRequest.put("preRegistrationId", notificationDto.getPreRegistrationId());
		innerRequest.put("appointmentDate", notificationDto.getAppointmentDate());
		innerRequest.put("appointmentTime", notificationDto.getAppointmentTime());
		innerRequest.put("mobNum", notificationDto.getMobNum());
		innerRequest.put("emailID", notificationDto.getEmailID());
		innerRequest.put("additionalRecipient", notificationDto.isAdditionalRecipient());
		innerRequest.put("isBatch", notificationDto.getIsBatch());
		innerRequest.put("languageCode", notificationDto.getLanguageCode());
		innerRequest.put("fullName", notificationDto.getFullName());
		innerRequest.put("registrationCenterName", notificationDto.getRegistrationCenterName());
		innerRequest.put("address", notificationDto.getAddress());
		innerRequest.put("langCode", notificationDto.getLangCode()); // Added for completeness

		Map<String, Object> outerRequest = new HashMap<>();
		outerRequest.put("id", notificationServiceId);
		outerRequest.put("version", version);
		outerRequest.put("requesttime", DateTimeFormatter.ofPattern(mosipDateTimeFormat).format(LocalDateTime.now(ZoneId.of("UTC"))));
		outerRequest.put("request", innerRequest);
		return outerRequest;
	}


	public MainResponseDTO<NotificationResponseDTO> sendNotification(String jsonString, String langCode,
																	 Object file, boolean isLatest) {

		MainResponseDTO<NotificationResponseDTO> response = new MainResponseDTO<>();
		response.setId(notificationServiceId);
		response.setVersion(version);
		response.setResponsetime(LocalDateTime.now(ZoneId.of("UTC")).format(DateTimeFormatter.ofPattern(mosipDateTimeFormat)));
		try {
			MainRequestDTO<NotificationDTO> notificationReqDTO = serviceUtil.createNotificationDetails(jsonString, langCode, isLatest);
			response.setId(notificationReqDTO.getId()); // Consistent ID/Version
			response.setVersion(notificationReqDTO.getVersion());
			NotificationDTO notificationDto = notificationReqDTO.getRequest();
			if (!validationUtil.requestValidator(validationUtil.prepareRequestMap(notificationReqDTO), requiredRequestMap)) {
				throw new InvalidRequestParameterException("REQ_VALIDATION_FAILED", "Request validation failed", null);
			}

			// Now, we have a validated NotificationDTO. Proceed with notification.
			log.info("Sending notification for pre-registration ID: {}", notificationDto.getPreRegistrationId());


			//THIS is where the notification logic should be.
			if (notificationDto.isAdditionalRecipient()) {
				log.info("Sending notification to additional recipient for pre-registration ID: {}", notificationDto.getPreRegistrationId());
				sendAdditionalRecipientNotification(notificationDto); // New method for clarity
			}
			else{
				sendNotificationBasedOnDemographicData(notificationDto,langCode,file);
			}

			NotificationResponseDTO notificationResponse = new NotificationResponseDTO(); // Initialize response
			notificationResponse.setMessage("Notification sent successfully."); // Or set a more specific message later
			response.setResponse(notificationResponse);

		} catch (Exception ex) {
			log.error("Error sending notification: {}", ex.getMessage(), ex);
			response.setErrors(convertServiceErrors(ex));

		}

		return response;
	}
	private void sendAdditionalRecipientNotification(NotificationDTO notificationDto) throws Exception {
		if (notificationDto.getMobNum() != null && !notificationDto.getMobNum().isEmpty()) {
			if (validationUtil.phoneValidator(notificationDto.getMobNum())) {
				// Create a simple map for the message.
				Map<String, String> smsValues = new HashMap<>();
				smsValues.put("message", "Your OTP is: 111111");  // Replace [OTP] with the actual OTP if you have it.
				// You might need to get the user ID from somewhere.
//				serviceUtil.invokeSmsNotification(smsValues, notificationDto.getMobNum(),  new MainRequestDTO<OtpRequestDTO>(), notificationDto.getLangCode()); // Correct method
				MainRequestDTO<OtpRequestDTO> dummyRequest = new MainRequestDTO<>();
				serviceUtil.invokeSmsNotification(smsValues,notificationDto.getMobNum(),dummyRequest,notificationDto.getLangCode());
			} else {
				throw new IllegalArgumentException("Invalid phone number: " + notificationDto.getMobNum());
			}
		}

		if (notificationDto.getEmailID() != null && !notificationDto.getEmailID().isEmpty()) {
			if (validationUtil.emailValidator(notificationDto.getEmailID())) {
				Map<String, String> emailValues = new HashMap<>();
				emailValues.put("subject", "Your Appointment Reminder"); //  set a subject
				emailValues.put("content", "Your appointment is scheduled for [date] at [time].");   // set content, and replace  placeholders
				// You might need to get the user ID from somewhere
//				serviceUtil.invokeEmailNotification(emailValues, notificationDto.getEmailID(), new MainRequestDTO<OtpRequestDTO>(), notificationDto.getLangCode());  // Correct method
				MainRequestDTO<OtpRequestDTO> dummyRequest = new MainRequestDTO<>();
				serviceUtil.invokeEmailNotification(emailValues, notificationDto.getEmailID(),dummyRequest, notificationDto.getLangCode());
			} else {
				throw new IllegalArgumentException("Invalid email address: " + notificationDto.getEmailID());
			}
		}

		if (notificationDto.getEmailID() == null && notificationDto.getMobNum() == null) {
			throw new IllegalArgumentException("Either mobile number or email address must be provided for additional recipient.");
		}
	}


	private void sendNotificationBasedOnDemographicData(NotificationDTO notificationDto, String langCode, Object file) throws  Exception{
		MainResponseDTO<DemographicResponseDTO> demoDetail =  notificationDtoValidation(notificationDto);
		if(demoDetail!=null && demoDetail.getResponse()!=null){
			//	String resp = getDemographicDetailsWithPreId(demoDetail, notificationDto, langCode, file);
			//Set response for this notification

			// Directly use the notificationDto to send SMS/Email
			if (notificationDto.getMobNum() != null && !notificationDto.getMobNum().isEmpty()) {
				Map<String, String> smsValues = new HashMap<>();
				smsValues.put("preRegistrationId", notificationDto.getPreRegistrationId()); // Example
				smsValues.put("appointmentDate", notificationDto.getAppointmentDate());    // Example
				smsValues.put("appointmentTime",notificationDto.getAppointmentTime());
				String smsLang = notificationDto.getLangCode() != null ? notificationDto.getLangCode() : (langCode != null ? langCode : "eng"); // Or your preferred default
				smsValues.put("registrationCenterName", getValueForLanguage(notificationDto.getRegistrationCenterName(), smsLang));
				smsValues.put("address", getValueForLanguage(notificationDto.getAddress(), smsLang));
				smsValues.put("userName", getValueForLanguage(notificationDto.getFullName(),smsLang));

//				serviceUtil.invokeSmsNotification(smsValues, notificationDto.getMobNum(), new MainRequestDTO<OtpRequestDTO>(), notificationDto.getLangCode());
				MainRequestDTO<OtpRequestDTO> dummyRequest = new MainRequestDTO<>();
				serviceUtil.invokeSmsNotification(smsValues, notificationDto.getMobNum(), dummyRequest, smsLang);
			}

			if (notificationDto.getEmailID() != null && !notificationDto.getEmailID().isEmpty()) {
				Map<String, String> emailValues = new HashMap<>();
				emailValues.put("preRegistrationId", notificationDto.getPreRegistrationId()); // Example
				emailValues.put("appointmentDate", notificationDto.getAppointmentDate());    // Example
				emailValues.put("appointmentTime", notificationDto.getAppointmentTime());  // Example
				emailValues.put("userName",notificationDto.getFullName().get(0).getValue());
				String emailLang = notificationDto.getLangCode() != null ? notificationDto.getLangCode() : (langCode != null ? langCode : "eng");
				emailValues.put("userName",getValueForLanguage(notificationDto.getFullName(),emailLang));

				// **FIX: Add registrationCenterName and address**
				emailValues.put("registrationCenterName", getValueForLanguage(notificationDto.getRegistrationCenterName(), emailLang));
				emailValues.put("address", getValueForLanguage(notificationDto.getAddress(), emailLang));

//				serviceUtil.invokeEmailNotification(emailValues, notificationDto.getEmailID(),  new MainRequestDTO<OtpRequestDTO>(), notificationDto.getLangCode());
				MainRequestDTO<OtpRequestDTO> dummyRequest = new MainRequestDTO<>();
				serviceUtil.invokeEmailNotification(emailValues, notificationDto.getEmailID(),  dummyRequest, emailLang);

			}

			if (notificationDto.getMobNum() == null && notificationDto.getEmailID() == null) {
				log.warn("Neither email nor phone number found in demographic data for pre-registration ID: {}", notificationDto.getPreRegistrationId());
			}

		}
		else{
			// If demographic data is missing or invalid, log and throw exception.
			log.error("Demographic data is missing or invalid for pre-registration ID: {}", notificationDto.getPreRegistrationId());
			throw new InvalidRequestParameterException("DEMOGRAPHIC_DATA_ERROR", "Demographic data is missing or invalid.", null);
		}

	}
	private String getValueForLanguage(List<KeyValuePairDto<String, String>> keyValuePairs, String languageCode) {
		if (keyValuePairs == null || keyValuePairs.isEmpty()) {
			return ""; // Or a suitable default, like "N/A"
		}
		for (KeyValuePairDto<String, String> pair : keyValuePairs) {
			if (pair.getKey().equals(languageCode)) {
				return pair.getValue();
			}
		}
		// Fallback: Return the value of the first entry if the specific language is not found.
		return keyValuePairs.get(0).getValue();
	}

	public MainResponseDTO<DemographicResponseDTO> notificationDtoValidation(NotificationDTO dto)
			throws IOException {
		// Reuse existing methods for fetching and validating demographic/appointment data.
		MainResponseDTO<DemographicResponseDTO> demoDetail = getDemographicDetails(dto);
		if(demoDetail==null || demoDetail.getResponse()==null){
			log.error("Demographic details not found for pre-registration ID: {}", dto.getPreRegistrationId());
			throw new InvalidRequestParameterException("DEMOGRAPHIC_DETAILS_NOT_FOUND", "Demographic details not found.", null);
		}
		return demoDetail;
	}
	public MainResponseDTO<DemographicResponseDTO> getDemographicDetails(NotificationDTO notificationDto)
			throws IOException {
		MainResponseDTO<DemographicResponseDTO> responseEntity = demographicService.getDemographicData(notificationDto.getPreRegistrationId());

		// Check for errors *before* accessing the response.
		if (responseEntity.getResponse() == null) {
			log.error("Demographic details not found for pre-registration ID: {}", notificationDto.getPreRegistrationId());
			if(responseEntity.getErrors()!=null){
				log.error("Error in fetching demographic data : {} ", responseEntity.getErrors());
			}
			return null; // Or throw a custom exception, depending on your needs
		}
		JsonNode responseNode = objectMapper.readTree(responseEntity.getResponse().getDemographicDetails().toJSONString());

		if (!responseNode.has(identity)) {
			log.error("The 'identity' field is missing from the demographic data for pre-registration ID: {}", notificationDto.getPreRegistrationId());
			return null;
		}
		return responseEntity;
	}
	

}
