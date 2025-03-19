import com.sap.gateway.ip.core.customdev.util.Message
import groovy.xml.MarkupBuilder

/**
 * createParty – Transforms a parsed Party XML node into the mapped PARTIES structure.
 *
 * @param partyNode the Party XML as a GPathResult (already parsed)
 * @return the mapped PARTIES XML (as a String)
 */
def String createParty(def partyNode) {
    // Extract the necessary fields directly from the parsed node
    def supplierPartyID       = partyNode.SupplierPartyID.text()
    def addressName           = partyNode.Address.AddressName.text()
    def addressAdditionalName = partyNode.Address.AddressAdditionalName.text()
    def streetName            = partyNode.Address.StreetName.text()
    def postalCode            = partyNode.Address.PostalCode.text()
    def cityName              = partyNode.Address.CityName.text()
    def email                 = partyNode.Address.EmailAddress.text()
    def streetAddressName     = partyNode.Address.StreetAddressName.text()
    def countryCode           = partyNode.Address.Country.text()
    
    // (Optional) If you need to collect any unmapped Address children
    def mappedFields = ["Country", "SupplierPartyID", "AddressName", "AddressAdditionalName",
                        "StreetName", "StreetAddressName", "PostalCode", "CityName", "EmailAddress"]
    def additionalProps = []
    partyNode.Address.children().each { child ->
        def childName = child.name().toString()
        if (!mappedFields.contains(childName)) {
            additionalProps << [name: childName, value: child.text()]
        }
    }
    
    // Build the mapped PARTIES XML using MarkupBuilder
    def sw = new StringWriter()
    def xml = new MarkupBuilder(sw)
    
    xml.PARTIES {
        Qualifier("SU")
        VAT_number(supplierPartyID ?: "")
        Address(streetName ?: "SU Address 1")
        Address_2(streetAddressName ?: "")
        Address_3("")
        City(cityName ?: "")
        Post_code(postalCode ?: "")
        Country_subdivision("")
        Country_code(countryCode ?: "")
        Legal_name((addressName + " " + addressAdditionalName).trim())
        Telephone("")
        Email(email ?: "")
        Fax("")
        Additional_ID_type("")
        // Optionally, loop through additionalProps if you want to add them:
        /*
        additionalProps.each { prop ->
            ADDITIONAL_PROPERTIES_PARTIES {
                Name(prop.name)
                Value(prop.value)
            }
        }
        */
    }
    
    return sw.toString()
}

/**
 * processData – The main function called by SAP CPI.
 * It reads the full input XML, extracts the Party element with PartyType="BillFrom",
 * calls createParty to map it, and then builds a larger response XML that includes the mapped fragment.
 *
 * @param message the SAP CPI message
 * @return the updated message with the response XML as its body
 */
def Message processData(Message message) {
    // Read the full input XML payload as a String
    def body = message.getBody(String)
    
    // Parse the entire XML using XmlSlurper
    def input = new XmlSlurper().parseText(body)
    
    // Find the Party element with PartyType="BillFrom" (adjust this if needed)
    def partyFragment = input.Invoice.Party.find { it.@PartyType == "BillFrom" }
    
    if (!partyFragment) {
        message.setBody("<Response><Error>Party with PartyType 'BillFrom' not found.</Error></Response>")
        return message
    }
    
    // Call createParty passing the already-parsed Party node.
    def mappedPartyXml = createParty(partyFragment)
    
    // Build the final response XML, appending the mapped party fragment.
    def sw = new StringWriter()
    def xml = new MarkupBuilder(sw)
    
    xml.Response {
        Header {
            MessageID("12345")
            Timestamp(new Date().format("yyyy-MM-dd'T'HH:mm:ss"))
        }
        Body {
            // Append the mapped party XML; use yieldUnescaped since it's already XML.
            mkp.yieldUnescaped(mappedPartyXml)
        }
    }
    
    message.setBody(sw.toString())
    return message
}
