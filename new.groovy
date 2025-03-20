import com.sap.gateway.ip.core.customdev.util.Message
import groovy.xml.MarkupBuilder
import java.io.StringWriter

def String createTax(def headerTax) {
    def amount              = headerTax.Amount?.text() ?: ''
    def taxCurrencyCode     = headerTax.Amount[0]?.@currencyCode ?: ''
    def taxPercentage       = headerTax.TaxPercentage?.text() ?: ''

    def sw = new StringWriter()
    def xml = new MarkupBuilder(sw)

    xml.TAX {
        Tax_amount(amount)
        Tax_category_ID("01")
        Tax_type("VAT")
        Tax_rate(taxPercentage)
        Tax_class("08")
        Tax_CurrencyCode(taxCurrencyCode)
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

def String createTaxLine(def itemTaxNode) {
    def amount = itemTaxNode.Amount.text() ?: ''
    def taxPercentage = itemTaxNode.TaxPercentage.text() ?: ''

    def writer = new StringWriter()
    def xml = new MarkupBuilder(writer)
    
    xml.TAX_LINE {
        Tax_category_ID("01") //hardcoded for now
        Tax_type("VAT") // hardcoded for now
        Tax_rate(taxPercentage)
        Taxable_amount("")
        Tax_amount(amount)
        Tax_class("08") // hardcoded for now
    }
    return writer.toString()
}

/**
 * createLine – Transforms an Item XML node into the mapped LINE structure.
 *
 * @param itemNode the parsed Item node (a GPathResult)
 * @return the mapped LINE XML as a String
 */
def String createLine(def itemNode) {

    def netPrice = ""
    def netValuePricing = itemNode.PricingElement.find { 
        it.SupplierConditionTypeName.text() == "Net Value 1" 
    }
    if(netValuePricing) {
        netPrice = netValuePricing.ConditionRateValue.text()
    } else {
        // Compute net price as NetAmount divided by InvoicedQuantity (if possible)
        try {
            def invoicedQuantity = itemNode.InvoicedQuantity.text().toBigDecimal()
            def itemNetAmount = itemNode.NetAmount.text().toBigDecimal()
            if(invoicedQuantity > 0) {
                netPrice = (itemNetAmount / invoicedQuantity)
                            .setScale(2, RoundingMode.HALF_UP)
                            .toString()
            }
        } catch(Exception ex) {
            // Log or handle error if conversion fails; leave netPrice as empty string.
            netPrice = ""
        }
    }

    // Compute Gross Price:
    def grossPrice = ""
    def grossValuePricing = itemNode.PricingElement.find { 
        it.SupplierConditionTypeName.text() == "Gross Value" 
    }
    if(grossValuePricing) {
        grossPrice = grossValuePricing.ConditionRateValue.text()
    } else {
        try {
            def invoicedQuantity = itemNode.InvoicedQuantity.text().toBigDecimal()
            def itemGrossAmount = itemNode.GrossAmount.text().toBigDecimal()
            if(invoicedQuantity > 0) {
                grossPrice = (itemGrossAmount / invoicedQuantity)
                              .setScale(2, RoundingMode.HALF_UP)
                              .toString()
            }
        } catch(Exception ex) {
            grossPrice = ""
        }
    }
    
    // Map net amount and gross amount
    def netAmount = itemNode.NetAmount.text() ?: ""
    def grossAmount = itemNode.GrossAmount.text() ?: ""
    def mappedTaxLines = []
    // Suppose itemNode.ItemTax returns one or more <ItemTax> nodes.
    itemNode.ItemTax.each { taxNode ->
        mappedTaxLines << createTaxLine(taxNode)
    }
    def allTaxLinesXml = mappedTaxLines.join("")

    def writer = new StringWriter()
    def xml = new MarkupBuilder(writer)
    
    xml.LINE {
        Line_ID(itemNode.SupplierInvoiceItemID.text() ?: "")
        Description(itemNode.BillingDocumentItemText.text() ?: "")
        Seller_identifier("")
        Quantity(itemNode.InvoicedQuantity.text() ?: "")
        Quantity_unit_code(itemNode.InvoicedQuantity.@unitCode?.toString() ?: "")
        Net_price(netPrice)
        Gross_price(grossPrice)
        Net_amount(netAmount)
        Gross_amount(grossAmount)
        Standard_identifier("")
        Net_price_with_line_allowances_charges(grossAmount)
        Net_amount_with_taxes(grossAmount)
        mkp.yieldUnescaped(allTaxLinesXml)
        // ADDITIONAL_GROUP_LINE {
        //     Name("LINE_AMOUNTS")
        //     ADDITIONAL_GROUP_LINE_PROPERTY {
        //         Name("LINE_AMOUNTS_NET_TAX_AMOUNT")
        //         Value("")
        //     }
        //     ADDITIONAL_GROUP_LINE_PROPERTY {
        //         Name("LINE_AMOUNTS_TOTAL_TAXABLE_AMOUNT")
        //         Value("")
        //     }
        //     ADDITIONAL_GROUP_LINE_PROPERTY {
        //         Name("LINE_AMOUNTS_FACTORY_TAX_AMOUNT")
        //         Value("")
        //     }
        // }
    }
    
    return writer.toString()
}

def String createPaymentInstruction(def paymentNode) {
    def writer = new StringWriter()
    def xml = new MarkupBuilder(writer)
    
    xml.PAYMENT_INSTRUCTIONS {
        Payment_due_date()
        Payment_means("")
        Payment_amount("")
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
    def headerTax = input.Invoice.HeaderTax
    def taxFragments = []
    input.Invoice.HeaderTax.each { headerTax ->
        taxFragments << createTax(headerTax)
        }
        
    mkp.yieldUnescaped(taxFragments.join('\n'))

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
    def items = input.Invoice.Item
    def mappedLines = []

    items.each { item ->
        // Call createLine for each Item node and add the result to our list.
        mappedLines << createLine(item)
        
    }

    def allLinesXml = mappedLines.join("")

    // Build the final response XML, appending the mapped party fragment.
    def sw = new StringWriter()
    def xml = new MarkupBuilder(sw)
    
    xml.Response {
        Header {
            Invoice_number("")
            Project("CR_DGT")
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
            PAYMENT_INSTRUCTIONS{
                Payment_due_date(input.Invoice.PaymentTerms.DueCalculationBaseDate)
                Payment_means("01")
                Payment_amount(input.Invoice.GrossAmount)
            }
            mkp.yieldUnescaped(allLinesXml)
        }
    }
    
    message.setBody(sw.toString())
    return message
}
