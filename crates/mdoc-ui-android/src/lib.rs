use anyhow::{Result, ensure};
use base64::{Engine, engine::general_purpose::STANDARD};
use mdoc_android_platform::EventSink;
use mdoc_core::{DeviceResponse, ElementValue, FullDate};
use mdoc_data_retrieval_flow::{DataRetrievalFlowEvent, DataRetrievalFlowObserver};
use mdoc_ui::{FlowEventUi, MdocResultUi};
use minicbor::bytes::ByteVec;
use serde_json::{Value, json};
use std::{collections::BTreeMap, io::Cursor, sync::Arc};

pub struct AndroidUi {
    pub events: Arc<dyn EventSink>,
    pub result: Option<String>,
}
impl FlowEventUi for AndroidUi {
    type Error = anyhow::Error;
    fn on_flow_event(&self, event: DataRetrievalFlowEvent) -> Result<()> {
        use DataRetrievalFlowEvent::*;
        self.events.on_event(
            match event {
                WaitingForEngagement(_) => "nfc_waiting",
                EngagementConnected(_) => "nfc_connected",
                TransportConnected(_) => "ble_connected",
                WaitingForUserApproval => "approval_waiting",
                DeviceResponseReceived => "validating",
            }
            .into(),
        );
        Ok(())
    }
}
impl DataRetrievalFlowObserver for AndroidUi {
    fn on_event(&self, event: DataRetrievalFlowEvent) {
        let _ = self.on_flow_event(event);
    }
}
impl MdocResultUi<()> for AndroidUi {
    type Error = anyhow::Error;
    fn render_result(&mut self, response: &DeviceResponse, _: &()) -> Result<()> {
        ensure!(
            response.status == 0,
            "DeviceResponse status={}",
            response.status
        );
        let docs = response
            .documents
            .as_ref()
            .filter(|d| !d.is_empty())
            .ok_or_else(|| anyhow::anyhow!("No documents returned"))?;
        let mut documents = vec![];
        for doc in docs {
            let mut fields = vec![];
            if let Some(namespaces) = &doc.issuer_signed.name_spaces {
                for (ns, items) in namespaces {
                    for item in items {
                        let item = item.decode()?;
                        let mut field = json!({"namespace": ns, "name": item.element_identifier,
                        "value": format_value(&item.element_value)});
                        if item.element_identifier == "portrait" {
                            let picture = item
                                .element_value
                                .decode::<ByteVec>()
                                .ok()
                                .and_then(|bytes| portrait_png(&bytes).ok());
                            if let Some(png) = picture {
                                field["image"] = json!(STANDARD.encode(png));
                            } else {
                                field["value"] = json!("Portrait could not be decoded");
                            }
                        }
                        fields.push(field);
                    }
                }
            }
            documents.push(json!({"docType": doc.doc_type, "fields": fields,
                "errors": doc.errors.as_ref().map(|e| format!("{e:?}"))}));
        }
        self.result = Some(
            json!({"documents": documents,
            "documentErrors": response.document_errors.as_ref().map(|e| format!("{e:?}"))})
            .to_string(),
        );
        Ok(())
    }
}
fn format_value(value: &ElementValue) -> Value {
    if let Ok(v) = value.decode::<String>() {
        return json!(v);
    }
    if let Ok(v) = value.decode::<FullDate>() {
        return json!(v.value());
    }
    // ISO/IEC 23220-2 defines birth_date as a map so that an optional
    // approximate_mask can describe unknown digits. The nested birth_date
    // member is a CBOR full-date (tag 1004), not the map itself.
    if let Ok(v) = value.decode::<BTreeMap<String, ElementValue>>() {
        if let Some(date) = v.get("birth_date") {
            let formatted = format_value(date);
            if formatted != json!("Unsupported CBOR value") {
                return formatted;
            }
        }
    }
    if let Ok(v) = value.decode::<bool>() {
        return json!(v);
    }
    if let Ok(v) = value.decode::<i64>() {
        return json!(v);
    }
    if let Ok(v) = value.decode::<u64>() {
        return json!(v);
    }
    if let Ok(v) = value.decode::<ByteVec>() {
        return json!(format!("Binary ({} bytes)", v.len()));
    }
    json!("Unsupported CBOR value")
}
fn portrait_png(bytes: &[u8]) -> Result<Vec<u8>> {
    let image = if let Ok(decoder) = hayro_jpeg2000::Image::new(bytes, &Default::default()) {
        use image::ImageDecoder;
        let (w, h) = decoder.dimensions();
        ensure!(w <= 4096 && h <= 4096, "Portrait dimensions too large");
        image::DynamicImage::from_decoder(decoder)?
    } else {
        let mut reader = image::ImageReader::new(Cursor::new(bytes)).with_guessed_format()?;
        let mut limits = image::Limits::default();
        limits.max_image_width = Some(4096);
        limits.max_image_height = Some(4096);
        reader.limits(limits);
        reader.decode()?
    };
    let mut out = Cursor::new(Vec::new());
    image
        .thumbnail(800, 800)
        .write_to(&mut out, image::ImageFormat::Png)?;
    Ok(out.into_inner())
}

#[cfg(test)]
mod tests {
    use super::format_value;
    use mdoc_core::{ElementValue, FullDate};
    use minicbor::Encoder;
    use serde_json::json;

    #[test]
    fn formats_iso_23220_birth_date_structure() {
        let mut encoder = Encoder::new(Vec::new());
        encoder.map(2).unwrap();
        encoder.str("birth_date").unwrap();
        encoder
            .encode(FullDate::from("2000-03-31".to_string()))
            .unwrap();
        encoder.str("approximate_mask").unwrap();
        encoder.str("00000000").unwrap();
        let value: ElementValue = minicbor::decode(&encoder.into_writer()).unwrap();

        assert_eq!(format_value(&value), json!("2000-03-31"));
    }

    #[test]
    fn formats_plain_and_full_date_values() {
        let plain: ElementValue =
            minicbor::decode(&minicbor::to_vec("2000-03-31").unwrap()).unwrap();
        let tagged: ElementValue =
            minicbor::decode(&minicbor::to_vec(FullDate::from("2000-03-31".to_string())).unwrap())
                .unwrap();

        assert_eq!(format_value(&plain), json!("2000-03-31"));
        assert_eq!(format_value(&tagged), json!("2000-03-31"));
    }
}
