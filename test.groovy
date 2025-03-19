import com.sap.gateway.ip.core.customdev.util.Message
import groovy.xml.MarkupBuilder
import java.io.StringWriter

def String createTax(def headerTax, def item) {
    def supplierTaxTypeCode = headerTax.SupplierTaxTypeCode?.text() ?: ''
    def amount              = headerTax.Amount?.text() ?: ''
    def taxCurrencyCode     = headerTax.Amount[0]?.@currencyCode ?: ''
    def taxPercentage       = headerTax.TaxPercentage?.text() ?: ''

    def itemTax             = item.ItemTax
    def taxPointDate        = itemTax?.TaxDeterminationDate?.text() ?: ''

    def sw = new StringWriter()
    def xml = new MarkupBuilder(sw)

    xml.TAX {
        Tax_amount(amount)
        Tax_type(supplierTaxTypeCode)
        Tax_rate(taxPercentage)
        Tax_CurrencyCode(taxCurrencyCode)
        Tax_point_date(taxPointDate)
    }

    return sw.toString()
}

def String createParty(def partyNode, def partyType) {
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
        Qualifier(partyType)
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
 * createLine – Transforms an Item XML node into the mapped LINE structure.
 *
 * @param itemNode the parsed Item node (a GPathResult)
 * @return the mapped LINE XML as a String
 */
def String createLine(def itemNode) {
    def writer = new StringWriter()
    def xml = new MarkupBuilder(writer)
    
    xml.LINE {
        Line_ID(itemNode.SupplierInvoiceItemID.text() ?: "")
        Description(itemNode.BillingDocumentItemText.text() ?: "")
        Seller_identifier("")
        Quantity(itemNode.InvoicedQuantity.text() ?: "")
        Quantity_unit_code(itemNode.InvoicedQuantity.@unitCode?.toString() ?: "")
        Net_price("")
        Net_amount("")
        Standard_identifier("")
        Net_price_with_line_allowances_charges("")
        Net_amount_with_taxes("")
        TAX_LINE {
            Tax_category_ID("")
            Tax_type("")
            Tax_rate("")
            Taxable_amount("")
            Tax_amount("")
            Tax_class("")
        }
        ADDITIONAL_GROUP_LINE {
            Name("LINE_AMOUNTS")
            ADDITIONAL_GROUP_LINE_PROPERTY {
                Name("LINE_AMOUNTS_NET_TAX_AMOUNT")
                Value("")
            }
            ADDITIONAL_GROUP_LINE_PROPERTY {
                Name("LINE_AMOUNTS_TOTAL_TAXABLE_AMOUNT")
                Value("")
            }
            ADDITIONAL_GROUP_LINE_PROPERTY {
                Name("LINE_AMOUNTS_FACTORY_TAX_AMOUNT")
                Value("")
            }
        }
    }
    
    return writer.toString()
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
    def partySUFragment = input.Invoice.Party.find { it.@PartyType == "BillTo" }
    def partyBYFragment = input.Invoice.Party.find { it.@PartyType == "SoldTo" }

    // Find the Tax element
    def headerTax = invoice.HeaderTax
    def items = invoice.Item
    def taxFragments = items.collect { item ->
        createTax(headerTax, item)
    }

    if (!partySUFragment) {
        message.setBody("<Response><Error>Party with PartyType 'BillTo' not found.</Error></Response>")
        return message
    }
    if (!partyBYFragment) {
        message.setBody("<Response><Error>Party with PartyType 'SoldTo' not found.</Error></Response>")
        return message
    }
    
    // Call createParty passing the already-parsed Party node.
    def mappedPartySUXml = createParty(partySUFragment,"SU")
    def mappedPartyBYXml = createParty(partyBYFragment,"BY")
    
    def currency_code = input.Invoice.GrossAmount.@currencyCode
    def vatCurrencyCode = input.Invoice.TaxAmount.@currencyCode

    // Build the final response XML, appending the mapped party fragment.
    def sw = new StringWriter()
    def xml = new MarkupBuilder(sw)
    
    xml.Response {
        Header {
            Invoice_number("")
            Project("")
            Date(input.Invoice.DocumentDate?: new Date().format("yyyy-MM-dd'T'HH:mm:ss"))
            Type("FE")
            UUID("")
            Copy_indicator("")
            Currency_code(currency_code?:"")
            VAT_currency_code(vatCurrencyCode?:"")
            Payment_terms_code("01")
            Exchange_rate(1)
            Payment_means("04")
            Total_VAT_amount(input.Invoice.TaxAmount)
            Invoice_total("")
            mkp.yieldUnescaped(mappedPartySUXml)
            mkp.yieldUnescaped(mappedPartyBYXml)
            mkp.yieldUnescaped(taxFragments.join('\n'))
        }
    }
    
    message.setBody(sw.toString())
    return message
}
