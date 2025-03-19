import com.sap.gateway.ip.core.customdev.util.Message
import groovy.xml.MarkupBuilder
import groovy.util.XmlSlurper

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

def Message processData(Message message) {
    def body = message.getBody(String)

    // Parse and ignore namespaces
    def parser = new XmlSlurper(false, false)
    parser.setFeature("http://xml.org/sax/features/namespaces", false)
    def input = parser.parseText(body)

    // Access Invoice
    def invoice = input.Invoice

    if (!invoice) {
        throw new IllegalArgumentException("Invoice not found!")
    }

    def headerTax = invoice.HeaderTax
    def items = invoice.Item

    if (!headerTax) {
        throw new IllegalArgumentException("HeaderTax is missing!")
    }

    if (!items || items.size() == 0) {
        throw new IllegalArgumentException("No Items found!")
    }

    // Collect TAX fragments for each item
    def taxFragments = items.collect { item ->
        createTax(headerTax, item)
    }

    // Build final XML response
    def sw = new StringWriter()
    def xml = new MarkupBuilder(sw)

    xml.Response {
        Header {
            MessageID('12345')
            Timestamp(new Date().format("yyyy-MM-dd'T'HH:mm:ss"))
        }
        Body {
            mkp.yieldUnescaped(taxFragments.join('\n'))
        }
    }

    message.setBody(sw.toString())
    return message
}
