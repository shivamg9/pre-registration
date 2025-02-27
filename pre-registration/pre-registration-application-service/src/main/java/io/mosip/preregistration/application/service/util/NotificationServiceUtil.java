package io.mosip.preregistration.application.service.util;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.mosip.kernel.core.exception.ExceptionUtils;
import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.kernel.core.templatemanager.spi.TemplateManager;
import io.mosip.kernel.core.util.exception.JsonProcessingException;
import io.mosip.preregistration.application.constant.PreRegLoginConstant;
import io.mosip.preregistration.application.constant.PreRegLoginErrorConstants;
import io.mosip.preregistration.application.dto.OtpRequestDTO;
import io.mosip.preregistration.application.dto.PreRegMailRequestDto;
import io.mosip.preregistration.application.dto.PreRegSmsRequestDto;
import io.mosip.preregistration.application.dto.PreRegSmsResponseDto;
import io.mosip.preregistration.application.entity.RegistrationCenterEntity;
import io.mosip.preregistration.application.exception.PreRegLoginException;
import io.mosip.preregistration.application.repository.ApplicationRepostiory;
//import io.mosip.preregistration.application.repository.RegistrationCenterRepository;
import io.mosip.preregistration.booking.dto.RegistrationCenterDto;
import io.mosip.preregistration.booking.dto.RegistrationCenterResponseDto;
import io.mosip.preregistration.core.common.dto.*;
import io.mosip.preregistration.core.config.LoggerConfiguration;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.apache.commons.io.IOUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class NotificationServiceUtil {

	private static final Logger log = LoggerConfiguration.logConfig(NotificationServiceUtil.class);

	@Value("${mosip.utc-datetime-pattern}")
	private String utcDateTimePattern;
	@Autowired
	private Environment environment;
	@Qualifier("selfTokenRestTemplate")
	@Autowired
	RestTemplate restTemplate;
	@Autowired
	TemplateManager templateManager;
	@Autowired
	private ObjectMapper objectMapper;  // Inject ObjectMapper
	@Value("${registrationcenter.centerdetail.rest.uri}")
	private String centerDetailUri;

	@Value("${regCenter.url}")
	String regCenterUrl;

//	@Autowired
//	private RegistrationCenterRepository registrationCenterRepository;

	private final String defaultLanguage; // Add this member variable

//In the constructor

	@Autowired
	public NotificationServiceUtil(  // Existing parameters
								   @Value("${preregistration.default-language:eng}") String defaultLanguage) {

		this.defaultLanguage = defaultLanguage;
	}



	private static final String LANG_CODE = "langcode";
	private static final String IS_ACTIVE = "isActive";
	private static final String TEMPLATE_TYPE_CODE = "templatetypecode";
	private ResponseWrapper<RegistrationCenterResponseDto> response;


	public MainRequestDTO<NotificationDTO> createNotificationDetails(String jsonString, String languageCode, boolean isLatest)
			throws IOException, DateTimeParseException {
		log.info("In createNotificationDetails method with body: {}", jsonString);

		MainRequestDTO<NotificationDTO> notificationReqDto = new MainRequestDTO<>();
		JsonNode notificationData = objectMapper.readTree(jsonString);
		JsonNode notificationDtoData = notificationData.get("request");

		NotificationDTO notificationDto = null;
		List<KeyValuePairDto<String, String>> languageNamePairs = new ArrayList<>();

		if (notificationData.has("requesttime") && !notificationData.get("requesttime").isNull()) {
			try {
				String requestTimeString = notificationData.get("requesttime").asText();
				LocalDateTime requestTime = LocalDateTime.parse(requestTimeString, DateTimeFormatter.ofPattern(utcDateTimePattern));
				notificationReqDto.setRequesttime(Date.from(requestTime.atZone(ZoneId.of("UTC")).toInstant()));
			} catch (DateTimeParseException ex) {
				log.error("Error parsing requesttime: {}", notificationData.get("requesttime").asText(), ex);
				throw new io.mosip.preregistration.core.exception.InvalidRequestParameterException(io.mosip.preregistration.core.errorcodes.ErrorCodes.PRG_CORE_REQ_003.getCode(), io.mosip.preregistration.core.errorcodes.ErrorMessages.INVALID_REQUEST_DATETIME.getMessage(), null);
			}
		} else {
			notificationReqDto.setRequesttime(null);
		}

		if (isLatest) {
			// Handle latest logic (if needed, usually not for notifications).  Review if this is actually necessary.
			//  It looks like an attempt to handle multiple languages, but it's overly complex.
			HashMap<String, String> result = objectMapper.readValue(notificationDtoData.toString(), HashMap.class);
			KeyValuePairDto<String, String> languageNamePair = null;
			for (Map.Entry<String, String> set : result.entrySet()) {
				languageNamePair = new KeyValuePairDto<>();
				notificationDto = objectMapper.convertValue(set.getValue(), NotificationDTO.class);
				languageNamePair.setKey(set.getKey());
				languageNamePair.setValue(notificationDto.getName());
				languageNamePairs.add(languageNamePair);
			}
			if (notificationDto != null) {
				notificationDto.setFullName(languageNamePairs);
				notificationDto.setLanguageCode(languageCode);
			}

		} else {
			// Correctly parse the NotificationDTO using ObjectMapper
			notificationDto = objectMapper.readValue(notificationDtoData.toString(), NotificationDTO.class);
			notificationDto.setLanguageCode(languageCode); //Simplified the logic here
		}


		notificationReqDto.setId(notificationData.get("id").asText());
		notificationReqDto.setVersion(notificationData.get("version").asText());
		notificationReqDto.setRequest(notificationDto);
		return notificationReqDto;
	}

	public void invokeSmsNotification(Map values, String userId, MainRequestDTO<OtpRequestDTO> requestDTO,
									  String langCode) throws PreRegLoginException, IOException {
		log.info("sessionId", "idType", "id", "In invokeSmsNotification method of notification service util");
		String otpSmsTemplate = environment.getProperty(PreRegLoginConstant.OTP_SMS_TEMPLATE);
		String reminderSmsTemplate = environment.getProperty(PreRegLoginConstant.REMINDER_SMS_TEMPLATE);
		String smsTemplate = applyTemplate(values, reminderSmsTemplate, langCode);
		sendSmsNotification(userId, smsTemplate, requestDTO);
	}

	/**
	 * Email notification.
	 *
	 * @param values          the values
//	 * @param emailId         the email id
//	 * @param sender          the sender
//	 * @param contentTemplate the content template
//	 * @param subjectTemplate the subject template
	 * @throws IOException
	 * @throws PreRegLoginException
//	 * @throws IdAuthenticationBusinessException the id authentication business
	 *                                           exception
	 */
	public void invokeEmailNotification(Map values, String userId,
										MainRequestDTO<OtpRequestDTO> requestDTO, String langCode) throws PreRegLoginException, IOException {
		log.info("sessionId", "idType", "id", "In invokeEmailNotification method of notification service util");
		String otpContentTemaplate = environment.getProperty(PreRegLoginConstant.OTP_CONTENT_TEMPLATE);
		String otpSubjectTemplate = environment.getProperty(PreRegLoginConstant.OTP_SUBJECT_TEMPLATE);
		String reminderContentTemplate = environment.getProperty(PreRegLoginConstant.REMINDER_CONTENT_TEMPLATE);
		String reminderSubjectTemplate = environment.getProperty(PreRegLoginConstant.REMINDER_SUBJECT_TEMPLATE);
		String mailSubject = applyTemplate(values, reminderSubjectTemplate, langCode);
		String mailContent = applyTemplate(values, reminderContentTemplate, langCode);
		sendEmailNotification(userId, mailSubject, mailContent, requestDTO);
	}

	/**
	 * Send sms notification.
	 *
	 * @param notificationMobileNo the notification mobile no
	 * @param message              the message
	 * @throws PreRegLoginException
	 */
	public void sendSmsNotification(String notificationMobileNo, String message, MainRequestDTO<OtpRequestDTO> requestDTO)
			throws PreRegLoginException {
		try {
			PreRegSmsRequestDto preRegSmsRequestDto = new PreRegSmsRequestDto();
			SMSRequestDTO smsRequestDto = new SMSRequestDTO();
			smsRequestDto.setMessage(message);
			smsRequestDto.setNumber(notificationMobileNo);
			preRegSmsRequestDto.setRequest(smsRequestDto);
			preRegSmsRequestDto.setId(requestDTO.getId());
			preRegSmsRequestDto.setRequesttime(LocalDateTime.now());
			preRegSmsRequestDto.setVersion(requestDTO.getVersion());

			HttpHeaders headers = new HttpHeaders();
			headers.setContentType(MediaType.APPLICATION_JSON);
			headers.setAccept(Arrays.asList(MediaType.APPLICATION_JSON));

			HttpEntity<PreRegSmsRequestDto> entity1 = new HttpEntity<PreRegSmsRequestDto>(preRegSmsRequestDto, headers);

			PreRegSmsResponseDto response = restTemplate.exchange(environment.getProperty("sms-notification.rest.uri"),
					HttpMethod.POST, entity1, PreRegSmsResponseDto.class).getBody();
			if (!response.getResponse().getStatus().equalsIgnoreCase(PreRegLoginConstant.SUCCESS))
				throw new PreRegLoginException(PreRegLoginErrorConstants.DATA_VALIDATION_FAILED.getErrorCode(),
						PreRegLoginErrorConstants.DATA_VALIDATION_FAILED.getErrorMessage());

		} catch (PreRegLoginException e) {
			log.error(PreRegLoginConstant.SESSION_ID, "Inside SMS Notification >>>>>", e.getErrorCode(),
					e.getErrorText());
			throw new PreRegLoginException(PreRegLoginErrorConstants.DATA_VALIDATION_FAILED.getErrorCode(),
					PreRegLoginErrorConstants.DATA_VALIDATION_FAILED.getErrorMessage());
		}
	}

	/**
	 * Send email notification.
	 *
	 * @param emailId     the email id
	 * @param mailSubject the mail subject
	 * @param mailContent the mail content
	 * @throws PreRegLoginException
	 */
	public void sendEmailNotification(String emailId, String mailSubject, String mailContent,
									  MainRequestDTO<OtpRequestDTO> requestDTO) throws PreRegLoginException {
		try {
			PreRegMailRequestDto mailRequestDto = new PreRegMailRequestDto();
			mailRequestDto.setMailSubject(mailSubject);
			mailRequestDto.setMailContent(mailContent);

			mailRequestDto.setMailTo(new String[] { emailId });

			LinkedMultiValueMap<String, Object> map = new LinkedMultiValueMap<>();
			map.add("mailContent", mailContent);
			map.add("mailSubject", mailSubject);
			map.add("mailTo", emailId);

			HttpHeaders headers1 = new HttpHeaders();

			headers1.setContentType(MediaType.MULTIPART_FORM_DATA);

			HttpEntity<LinkedMultiValueMap<String, Object>> entity1 = new HttpEntity<LinkedMultiValueMap<String, Object>>(
					map, headers1);

			PreRegSmsResponseDto response = restTemplate.exchange(environment.getProperty("mail-notification.rest.uri"),
					HttpMethod.POST, entity1, PreRegSmsResponseDto.class).getBody();

			if (!response.getResponse().getStatus().equalsIgnoreCase(PreRegLoginConstant.SUCCESS))
				throw new PreRegLoginException(PreRegLoginErrorConstants.DATA_VALIDATION_FAILED.getErrorCode(),
						PreRegLoginErrorConstants.DATA_VALIDATION_FAILED.getErrorMessage());

		} catch (PreRegLoginException e) {

			log.error(PreRegLoginConstant.SESSION_ID, "Inside Mail Notification >>>>>", e.getErrorCode(),
					e.getErrorText());
			throw new PreRegLoginException(PreRegLoginErrorConstants.DATA_VALIDATION_FAILED.getErrorCode(),
					PreRegLoginErrorConstants.DATA_VALIDATION_FAILED.getErrorMessage());
		}
	}

	/**
	 * To apply Template for PDF Generation.
	 *
	 * @param templateName - template name for pdf format
//	 * @param values       - list of contents
	 * @return the string
//	 * @throws IdAuthenticationBusinessException the id authentication business
	 *                                           exception
	 * @throws IOException                       Signals that an I/O exception has
	 *                                           occurred.
	 */

	public String applyTemplate(Map mp, String templateName, String langCode)

			throws PreRegLoginException, IOException {
		log.info("In applyTemplate of NotificationServiceUtil for templateName {} and values {}", templateName, mp);
		Objects.requireNonNull(templateName);
		Objects.requireNonNull(mp);
		StringWriter writer = new StringWriter();
		InputStream templateValue;
		String fetchedTemplate = fetchTemplate(templateName, langCode);
		templateValue = templateManager
				.merge(new ByteArrayInputStream(fetchedTemplate.getBytes(StandardCharsets.UTF_8)), mp);
		if (templateValue == null) {
			throw new PreRegLoginException(PreRegLoginErrorConstants.MISSING_INPUT_PARAMETER.getErrorCode(),
					String.format(PreRegLoginErrorConstants.MISSING_INPUT_PARAMETER.getErrorMessage(), "TEMPLATE"));
		}
		IOUtils.copy(templateValue, writer, StandardCharsets.UTF_8);
		return writer.toString();
	}

	/**
	 * Fetch Templates for e-KYC based on Template name.
	 *
	 * @param templateName the template name
	 * @return the string
//	 * @throws IdAuthenticationBusinessException the id authentication business
	 *                                           exception
	 */
	public String fetchTemplate(String templateName, String langCode) throws PreRegLoginException {
		log.info("In fetchTemplate of NotificationServiceUtil for templateName {}", templateName);
		Map<String, String> params = new HashMap<>();
		params.put(LANG_CODE, langCode);
		params.put(TEMPLATE_TYPE_CODE, templateName);

		HttpHeaders headers1 = new HttpHeaders();

		headers1.setAccept(Arrays.asList(MediaType.APPLICATION_JSON));

		HttpEntity entity1 = new HttpEntity<>(headers1);

		String url = UriComponentsBuilder
				.fromUriString(environment.getProperty("id-masterdata-template-service-multilang.rest.uri"))
				.buildAndExpand(params).toString();

		Map<String, Object> response = restTemplate.exchange(url, HttpMethod.GET, entity1, Map.class, templateName)
				.getBody();

		Map<String, List<Map<String, Object>>> fetchResponse;
		if (response instanceof Map) {
			fetchResponse = (Map<String, List<Map<String, Object>>>) response.get("response");
		} else {
			fetchResponse = Collections.emptyMap();
		}

		List<Map<String, Object>> masterDataList = fetchResponse.get("templates");
		Map<String, Map<String, String>> masterDataMap = new HashMap<>();
		for (Map<String, Object> map : masterDataList) {
			String lang = String.valueOf(map.get("langCode"));
			if (!params.containsKey("langCode")
					|| (params.containsKey("langCode") && lang.contentEquals(params.get("langCode")))) {
				String key = String.valueOf(map.get("templateTypeCode"));
				String value = String.valueOf(map.get("fileText"));
				Object isActiveObj = map.get(IS_ACTIVE);
				if (isActiveObj instanceof Boolean && (Boolean) isActiveObj) {
					Map<String, String> valueMap = masterDataMap.computeIfAbsent(lang,
							k -> new LinkedHashMap<String, String>());
					valueMap.put(key, value);
				}
			}
		}

		return Optional.ofNullable(masterDataMap.get(params.get(LANG_CODE))).map(map -> map.get(templateName))
				.orElse("");

	}

public NotificationDTO modifyCenterNameAndAddress(NotificationDTO notificationDto, String registrationCenterId,
												  String langCode) {
	log.info("In modifyCenterNameAndAddress of NotificationServiceUtil for registrationCenterId {}",
			registrationCenterId);

	if (notificationDto == null) {
		return null;
	}

	String userLangCode = (langCode != null && !langCode.isEmpty()) ? langCode : defaultLanguage;
	log.info("Fetching Registration center details for id {} and language code: {} ", registrationCenterId,
			userLangCode);

	RegistrationCenterDto centerDto = getRegistrationCenter(registrationCenterId, userLangCode);

	List<KeyValuePairDto<String, String>> centerNameList = new ArrayList<>();
	List<KeyValuePairDto<String, String>> addressList = new ArrayList<>();

	if (notificationDto.getFullName() != null && !notificationDto.getFullName().isEmpty()) {
		for (KeyValuePairDto<String, String> nameEntry : notificationDto.getFullName()) {
			userLangCode = nameEntry.getKey(); // Language from the user's name
			log.info("Processing name entry for pre-registration ID: {} and language code: {}",
					notificationDto.getPreRegistrationId(), userLangCode);
			if (centerDto != null) {
				KeyValuePairDto<String, String> centerNamePair = new KeyValuePairDto<>();
				centerNamePair.setKey(userLangCode);
				centerNamePair.setValue(Objects.toString(centerDto.getName(), ""));
				centerNameList.add(centerNamePair);

				KeyValuePairDto<String, String> addressPair = new KeyValuePairDto<>();
				addressPair.setKey(userLangCode);
				addressPair.setValue(buildAddress(centerDto));
				addressList.add(addressPair);

			} else {
				log.warn("No registration center details found for ID {} ", registrationCenterId);

			}
		}
	} else {
		log.warn("Full name field is empty in demographic data for pre-registration ID: {}",
				notificationDto.getPreRegistrationId());
	}

	notificationDto.setRegistrationCenterName(centerNameList);
	notificationDto.setAddress(addressList);
	return notificationDto;
}

	private String buildAddress(RegistrationCenterDto centerDto) {
		StringBuilder addressBuilder = new StringBuilder();
		if (centerDto.getAddressLine1() != null) {
			addressBuilder.append(centerDto.getAddressLine1());
		}
		if (centerDto.getAddressLine2() != null) {
			addressBuilder.append(" ").append(centerDto.getAddressLine2());
		}
		if (centerDto.getAddressLine3() != null) {
			addressBuilder.append(" ").append(centerDto.getAddressLine3());
		}
		return addressBuilder.toString().trim(); //Remove unnecessary spaces if present
	}


	public RegistrationCenterDto getRegistrationCenter(String registrationCenterId, String langCode) {
		log.info("Fetching registration center details for ID: {} and language: {}", registrationCenterId, langCode);

		String url = regCenterUrl + "/" + registrationCenterId + "/" + langCode;
		log.info("Registration Center Details URL: {}", url); // Log the *final* URL

		try {
			HttpHeaders headers = new HttpHeaders();
			headers.setContentType(MediaType.APPLICATION_JSON); // Important for REST calls
			HttpEntity<?> entity = new HttpEntity<>(headers);

			ResponseEntity<MainResponseDTO<RegistrationCenterDto>> responseEntity = restTemplate.exchange(
					url,
					HttpMethod.GET,
					entity,
					new ParameterizedTypeReference<MainResponseDTO<RegistrationCenterDto>>() {}); // Corrected parameterized type

			if (responseEntity.getStatusCode().is2xxSuccessful()) {
				MainResponseDTO<RegistrationCenterDto> body = responseEntity.getBody();
				if (body != null && body.getResponse() != null) {
					return body.getResponse();
				} else {
					log.warn("Registration center details not found for ID {} and language code {}", registrationCenterId, langCode);
					return null;
				}
			} else {
				log.error("Failed to retrieve registration center details. Status code: {}",
						responseEntity.getStatusCode());
				return null;
			}

		} catch (RestClientException ex) {
			log.error("Error fetching registration center details: " + ex.getMessage(), ex);
			return null; // Or throw a custom exception
		}
	}
}