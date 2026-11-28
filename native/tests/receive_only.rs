use kat_rx::{catalog,identify};

fn princeton()->Vec<i32>{
    let mut p=vec![-11700];
    for _ in 0..4{
        for c in "101001011001011010010110".chars(){if c=='1'{p.extend([1170,-390]);}else{p.extend([390,-1170]);}}
        p.extend([390,-11700]);
    }
    p
}

#[test]fn original_catalog_is_complete(){let protocols=catalog();println!("Catalog contains {} registered decoders",protocols.len());std::fs::write("catalog.json",serde_json::to_string_pretty(&protocols).unwrap()).unwrap();assert_eq!(protocols.len(),169);for name in ["Princeton","CAME","Nice FLO","VAG","PSA","Ford V0"]{assert!(protocols.iter().any(|p|p.name==name),"Missing {name}");}}
#[test]fn original_registry_recognizes_a_synthetic_fixed_code_format(){let found=identify(&princeton(),433_920_000,false).unwrap();assert!(found.iter().any(|r|r.name=="Princeton"),"{found:?}");}
#[test]fn no_payload_or_credentials_are_returned(){let json=serde_json::to_value(identify(&princeton(),433_920_000,false).unwrap()).unwrap();for entry in json.as_array().unwrap(){let keys=entry.as_object().unwrap().keys().map(String::as_str).collect::<Vec<_>>();assert_eq!(keys,vec!["bits","integrity","name"]);}}
#[test]fn malformed_input_and_noise_are_rejected(){assert!(identify(&[0],433_920_000,false).is_err());assert!(identify(&[i32::MIN],433_920_000,false).is_err());assert!(identify(&vec![100;32769],433_920_000,false).is_err());assert!(identify(&[41,-72,95,-18],433_920_000,false).unwrap().is_empty());}

#[test]fn distinct_fixed_formats_and_lengths_are_received(){
    for (name,te,gap,bits) in [("CAME",320,47,12),("CAME",320,76,24),("Nice FLO",700,36,12),("Nice FLO",700,36,24)]{
        let mut pulses=vec![-te*gap];
        for _ in 0..4{
            pulses.push(te);
            for bit in "101001011001011010010110".chars().take(bits){
                if bit=='1'{pulses.extend([-te*2,te]);}else{pulses.extend([-te,te*2]);}
            }
            pulses.push(-te*gap);
        }
        let found=identify(&pulses,433_920_000,false).unwrap();
        assert!(found.iter().any(|r|r.name==name&&r.bits==bits),"Expected {name}/{bits}, got {found:?}");
    }
}
